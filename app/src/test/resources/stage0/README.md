# Stage 0 deterministic fixtures

The two `plan.pdf` files intentionally have the same display filename while living under separate resource directories and containing different text markers. The cropped/rotated PDF has selectable text, a non-zero crop-box offset, and `/Rotate 90`. The scanned PDF contains a small embedded raster image and no text object. The blueprint PDF is a four-page vector-grid fixture with a deterministic 36 KB-scale payload.

The high-resolution phone photo is generated on the JVM by `HighResolutionPhonePhotoFixture` because committing a multi-megapixel binary is unnecessary for Stage 0. The generator produces deterministic 4032x3024 JPEG bytes.

`fully_populated_page_data.json` mirrors the current Drive JSON field names. The tests also construct the same domains with the public legacy model classes and exercise Java serialization for the local `PageMarkups` state.
