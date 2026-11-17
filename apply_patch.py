"""Adds the movie row to the CleanYourTV launcher. Run from the repo root; MovieRow.java sits next to this file."""
import pathlib
import shutil
import sys

main = pathlib.Path("launcher/app/src/main")
java = main / "java/com/gathin/tvhome"


def edit(path, old, new, count=1):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != count:
        sys.exit(f"patch failed: expected {count}x {old!r} in {path}, found {text.count(old)}")
    path.write_text(text.replace(old, new), encoding="utf-8")


shutil.copy("MovieRow.java", java / "MovieRow.java")

# Hook the row into the home screen.
act = java / "MainActivity.java"
edit(act, "        setContentView(R.layout.activity_main);\n",
     "        setContentView(R.layout.activity_main);\n        MovieRow.attach(this);\n")
edit(act, "        super.onResume();\n        resumed = true;\n",
     "        super.onResume();\n        resumed = true;\n        MovieRow.refresh();\n")

# Permissions: network (TMDB posters) and reading videos.
edit(main / "AndroidManifest.xml",
     '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
     '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n'
     '    <uses-permission android:name="android.permission.INTERNET" />\n'
     '    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />\n'
     '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />')

# One row of apps at the bottom (dock_row is already on by default), so the movie row has room above it.
cfg = main / "res/values/config.xml"
edit(cfg, '<bool name="single_row">false</bool>', '<bool name="single_row">true</bool>')
print("patch applied")
