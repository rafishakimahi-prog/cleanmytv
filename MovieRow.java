package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Row of vertical movie cards between the top bar and the dock. Shows local videos longer than 40 minutes.
 * Posters come from TMDB only when assets/tmdb_key.txt holds a key; otherwise a frame of the video is used.
 */
final class MovieRow {
    private static final int MIN_MS = 40 * 60 * 1000;
    private static final int REQ = 7331;
    private static final int AMBER = 0xFFFFB300;
    private static final Map<Integer, String> GENRES = new HashMap<>();
    private static MovieRow cur;
    private static boolean asked;

    static {
        String g = "28=Action,12=Adventure,16=Animation,35=Comedy,80=Crime,99=Documentary,18=Drama,10751=Family,"
                + "14=Fantasy,36=History,27=Horror,10402=Music,9648=Mystery,10749=Romance,878=Sci-Fi,53=Thriller,"
                + "10752=War,37=Western";
        for (String p : g.split(",")) {
            String[] kv = p.split("=");
            GENRES.put(Integer.parseInt(kv[0]), kv[1]);
        }
    }

    static void attach(Activity a) {
        try {
            cur = new MovieRow(a);
            cur.load();
        } catch (Exception e) {
            cur = null; // never break the launcher over this row
        }
    }

    static void refresh() {
        if (cur != null) cur.load();
    }

    static final class Item {
        long id;
        Uri uri;
        String title = "", year = "", genres = "";
        long ms;
        Bitmap bmp;
        boolean poster;
    }

    private final Activity act;
    private final float density;
    private final LinearLayout section;
    private final RecyclerView rv;
    private final TextView tTitle, tMeta;
    private final List<Item> items = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final int gridId = R.id.grid;
    private volatile boolean loading;
    private String key;

    private MovieRow(Activity a) {
        act = a;
        density = a.getResources().getDisplayMetrics().density;
        section = new LinearLayout(a);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setClipChildren(false);
        section.setClipToPadding(false);
        section.setVisibility(View.GONE);

        tTitle = new TextView(a);
        tTitle.setTextColor(Color.WHITE);
        tTitle.setTextSize(22);
        tTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tTitle.setSingleLine(true);
        tMeta = new TextView(a);
        tMeta.setTextColor(0xFFB0B8C8);
        tMeta.setTextSize(13);
        tMeta.setSingleLine(true);
        section.addView(tTitle);
        section.addView(tMeta);

        rv = new RecyclerView(a);
        rv.setId(View.generateViewId());
        rv.setLayoutManager(new LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setPadding(dp(4), dp(10), dp(4), dp(10));
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setItemAnimator(null);
        rv.setAdapter(adapter);
        section.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(192)));

        // Sits directly above the app grid, below the top bar.
        ViewGroup wrapper = (ViewGroup) a.findViewById(gridId).getParent();
        ViewGroup col = (ViewGroup) wrapper.getParent();
        col.addView(section, col.indexOfChild(wrapper),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void load() {
        if (loading) return;
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (act.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            if (!asked) {
                asked = true;
                act.requestPermissions(new String[]{perm}, REQ); // result is picked up by refresh() in onResume
            }
            return;
        }
        loading = true;
        io.execute(() -> {
            List<Item> found = query();
            if (sameAs(found)) {
                loading = false;
                return;
            }
            act.runOnUiThread(() -> show(found));
            for (int i = 0; i < found.size(); i++) {
                enrich(found.get(i));
                final int pos = i;
                act.runOnUiThread(() -> adapter.notifyItemChanged(pos));
            }
            loading = false;
        });
    }

    private boolean sameAs(List<Item> found) {
        if (found.size() != items.size()) return false;
        for (int i = 0; i < found.size(); i++) if (found.get(i).id != items.get(i).id) return false;
        return true;
    }

    private void show(List<Item> found) {
        items.clear();
        items.addAll(found);
        adapter.notifyDataSetChanged();
        boolean any = !items.isEmpty();
        section.setVisibility(any ? View.VISIBLE : View.GONE);
        if (any) showInfo(items.get(0));
        // Top-bar buttons point down at the grid; send them to this row while it has cards.
        retarget(act.getWindow().getDecorView(), any ? gridId : rv.getId(), any ? rv.getId() : gridId);
    }

    private static void retarget(View v, int from, int to) {
        if (v.getNextFocusDownId() == from) v.setNextFocusDownId(to);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) retarget(g.getChildAt(i), from, to);
        }
    }

    private void showInfo(Item it) {
        tTitle.setText(it.title);
        long mins = it.ms / 60000;
        StringBuilder m = new StringBuilder("FILM");
        if (!it.year.isEmpty()) m.append(" \u00b7 ").append(it.year);
        if (!it.genres.isEmpty()) m.append(" \u00b7 ").append(it.genres);
        m.append(" \u00b7 ").append(mins / 60).append("h ").append(mins % 60).append("m");
        tMeta.setText(m);
    }

    private void play(Item it) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, "video/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---- data ----

    private List<Item> query() {
        List<Item> out = new ArrayList<>();
        Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION};
        try (Cursor c = act.getContentResolver().query(base, cols, MediaStore.Video.Media.DURATION + ">=?",
                new String[]{String.valueOf(MIN_MS)}, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0);
                it.uri = ContentUris.withAppendedId(base, it.id);
                it.ms = c.getLong(2);
                parse(c.getString(1) == null ? "" : c.getString(1), it);
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void parse(String name, Item it) {
        String s = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        s = s.replaceAll("[._\\-]", " ");
        String year = "";
        Matcher y = Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(s);
        if (y.find()) {
            year = y.group();
            if (y.start() > 0) s = s.substring(0, y.start());
        }
        Matcher t = Pattern.compile("(?i)\\b(480p|576p|720p|1080p|2160p|4k|bluray|brrip|bdrip|webrip|hdrip|dvdrip|x264|x265|hevc)\\b").matcher(s);
        if (t.find() && t.start() > 0) s = s.substring(0, t.start());
        s = s.replaceAll("[\\[(].*?[\\])]", " ").replaceAll("\\s+", " ").trim();
        while (s.endsWith("(") || s.endsWith("[")) s = s.substring(0, s.length() - 1).trim();
        it.title = s.isEmpty() ? name : s;
        it.year = year;
    }

    private void enrich(Item it) {
        try {
            SharedPreferences p = act.getSharedPreferences("movierow", 0);
            String c = p.getString("m" + it.id, null);
            if (c == null && !key().isEmpty()) {
                c = tmdb(it);
                if (c != null) p.edit().putString("m" + it.id, c).apply();
            }
            if (c != null) {
                JSONObject j = new JSONObject(c);
                it.year = j.optString("y", it.year);
                it.genres = j.optString("g", "");
                String url = j.optString("p", "");
                if (!url.isEmpty()) {
                    it.bmp = poster(it.id, url);
                    it.poster = it.bmp != null;
                }
            }
        } catch (Exception ignored) {
        }
        if (it.bmp == null) it.bmp = thumb(it.uri);
    }

    private String key() {
        if (key == null) {
            key = "";
            try (InputStream in = act.getAssets().open("tmdb_key.txt")) {
                key = new String(readAll(in), "UTF-8").trim();
            } catch (Exception ignored) {
            }
        }
        return key;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    /** Returns {"p":posterUrl,"g":genres,"y":year}; "{}" when TMDB has no match; null when the request failed. */
    private String tmdb(Item it) throws Exception {
        String u = "https://api.themoviedb.org/3/search/movie?api_key=" + key()
                + "&query=" + URLEncoder.encode(it.title, "UTF-8") + (it.year.isEmpty() ? "" : "&year=" + it.year);
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) return null;
            JSONArray r = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8")).getJSONArray("results");
            JSONObject o = new JSONObject();
            if (r.length() > 0) {
                JSONObject m = r.getJSONObject(0);
                if (!m.isNull("poster_path")) o.put("p", "https://image.tmdb.org/t/p/w342" + m.getString("poster_path"));
                JSONArray ids = m.optJSONArray("genre_ids");
                StringBuilder g = new StringBuilder();
                for (int i = 0; ids != null && i < ids.length() && i < 2; i++) {
                    String n = GENRES.get(ids.getInt(i));
                    if (n != null) g.append(g.length() > 0 ? " \u00b7 " : "").append(n);
                }
                o.put("g", g.toString());
                o.put("y", m.optString("release_date", "").length() >= 4 ? m.optString("release_date").substring(0, 4) : "");
            }
            return o.toString();
        } finally {
            c.disconnect();
        }
    }

    private Bitmap poster(long id, String url) {
        try {
            File f = new File(act.getCacheDir(), "poster_" + id + ".jpg");
            if (!f.exists()) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    out.write(readAll(in));
                } finally {
                    c.disconnect();
                }
            }
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap thumb(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return act.getContentResolver().loadThumbnail(uri, new Size(320, 480), null);
            return MediaStore.Video.Thumbnails.getThumbnail(act.getContentResolver(), ContentUris.parseId(uri),
                    MediaStore.Video.Thumbnails.MINI_KIND, null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- cards ----

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView img;
        final TextView label;

        Holder(FrameLayout card, ImageView img, TextView label) {
            super(card);
            this.img = img;
            this.label = label;
        }
    }

    private final RecyclerView.Adapter<Holder> adapter = new RecyclerView.Adapter<Holder>() {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            FrameLayout card = new FrameLayout(act);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(112), dp(168));
            lp.setMargins(dp(6), 0, dp(6), 0);
            card.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF16213A);
            bg.setCornerRadius(dp(12));
            card.setBackground(bg);
            card.setClipToOutline(true);
            card.setFocusable(true);
            card.setClickable(true);
            ImageView img = new ImageView(act);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            TextView label = new TextView(act);
            label.setTextColor(Color.WHITE);
            label.setTextSize(11);
            label.setMaxLines(2);
            label.setBackgroundColor(0x99000000);
            label.setPadding(dp(6), dp(4), dp(6), dp(4));
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -2);
            llp.gravity = Gravity.BOTTOM;
            card.addView(label, llp);
            return new Holder(card, img, label);
        }

        @Override
        public void onBindViewHolder(Holder h, int pos) {
            final Item it = items.get(pos);
            h.img.setImageBitmap(it.bmp);
            h.label.setText(it.title);
            h.label.setVisibility(it.poster ? View.GONE : View.VISIBLE);
            h.itemView.setOnClickListener(v -> play(it));
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(120).start();
                GradientDrawable ring = null;
                if (focused) {
                    ring = new GradientDrawable();
                    ring.setStroke(dp(3), AMBER);
                    ring.setCornerRadius(dp(12));
                    showInfo(it);
                }
                ((FrameLayout) v).setForeground(ring);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    };
}
