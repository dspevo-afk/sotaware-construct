# Project folders

The home screen lists saved projects. Choose **Add folder** to select a folder
with Android's folder picker. The app remembers the read grant and displays its
subfolders and PDF drawings. Back moves up one folder; **All projects** returns
to the project list. A single PDF can still be opened from **Open PDF**.

Each project has a **Recent files** section above its folder contents. It keeps
the five most recently opened drawings, with their folder labels. Only successful
document opens are recorded. Existing document IDs and exact saved source
associations are retained; filenames never identify drawings.

Long-press an annotation tool to save its defaults for this project on this
device. See [drawing and photo tools](annotation-tools.md) for appearance editing,
polyline measurements, and photo annotations.

## Download projects from Google Drive

1. Choose **Google Drive**, then **Connect Google Drive**.
2. Browse or use **Find a folder in Drive** to find the source folder, for
   example **New Bid Documents**, even when it is nested or shared. Open it and select
   **Use this folder**. This choice is remembered for that Google account.
3. Use the download button beside a project folder. Alternatively, open a
   subfolder and choose **Download this project**.
4. The complete downloaded project appears on the home screen and is available
   offline, including its subfolders and PDF drawings.

Project imports request `https://www.googleapis.com/auth/drive.readonly` when
the user explicitly connects this browser. This permits listing and downloading
existing Drive content. The browser exposes no Drive write operations. Tokens
remain in memory, consent results retain their operation IDs, and leaving the
browser closes its read session. Both authorization requests exclude previously
granted scopes so their tokens stay separate. The annotation backup connection still
requests `drive.file` through its existing authorization and sync owners.

The OAuth consent configuration must include `drive.readonly` for this feature.
Google classifies it as a restricted scope; external distribution has additional
verification requirements. See [Google's scope documentation](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).
No client ID, signing configuration, or dependency changes are required by this
implementation. An unconfigured build explains that Google sign-in needs setup.

## Download and recovery behavior

- Imports include PDF drawings and the folder hierarchy. Other file types and
  Drive shortcuts are not imported. No Drive content is modified.
- Downloads use stable account and Drive folder/file IDs, paginated listings,
  bounded transfer sizes, checksum checks, PDF validation, and revision checks.
  Changed or incomplete listings and failed downloads do not become ready projects.
- Remote names are display labels. App-generated identifiers determine local
  paths, allowing same-name files and folders without overwriting one another.
- A completed download is an immutable local copy. Selecting it again opens the
  existing project, preserving its drawings and annotations; it does not silently
  replace source PDFs with newer Drive versions.
- Local folder access depends on the provider and persisted read grant. Missing
  or inaccessible folders show a retry/reconnect state rather than an empty success.
- Downloads are stored privately by the app. Removing the app removes those local
  copies and its local annotations. Original local folders and Drive files remain
  at their source.

Bounds: 64 projects, five recent drawings per project, 24 nested folder levels,
2,000 entries per download or local folder listing, 128 MiB per downloaded PDF,
and 1 GiB of PDF data per download. Full project downloads are admitted atomically;
no partial subset is labeled complete.

The feature tests are in `projects/` under the JVM and Android instrumentation
source sets. The native workflow uses the test APK's synthetic documents provider
through the real Android folder picker, checks subfolder navigation and per-project
recents, and reopens downloaded PDFs without a Drive connection. It restores the
previous project index and recent list after testing.
