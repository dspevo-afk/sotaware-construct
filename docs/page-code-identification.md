# Identify drawing page codes

Open a drawing page and choose **Menu → Identify page codes** from the three-line
button at the upper right. Zoom in first if the code is small, then drag a box around
the code in the title block. The app reads that same normalized area on every
page, including pages that are not currently visible.

When the scan finishes, **View Pages** shows each recognized code beside its
one-based page number. Pages without readable text in that area keep their
ordinary sheet number. Select a tighter box if it includes unrelated title-block
text. Cancel stops the scan; failed scans keep the previous labels.

Codes are derived display labels cached privately for the exact document and
source fingerprint. They survive reopening and activity recreation while that
cache is present. They do not rename PDFs, alter annotations, or become a new
save-bundle domain. Clearing the app cache or opening a separate imported copy
requires identifying its codes again.

A flick pans the PDF or full-screen photo after release. Touching the drawing,
changing tools, or leaving the viewer stops that motion. Annotation drags,
text selection, pinch gestures and canceled touches do not start a fling.
