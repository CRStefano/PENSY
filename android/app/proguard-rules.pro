# PENSY uses no reflection. Activities, receivers and providers are kept
# through the manifest; ZXing and ML Kit ship their own rules.

# The text reader is called through JNI by these exact names (see tools/ocr/pensyocr.cpp).
-keep class app.pensy.ocr.Tess { *; }
