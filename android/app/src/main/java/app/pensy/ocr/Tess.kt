package app.pensy.ocr

/**
 * The two native calls of libpensyocr.so (tools/ocr/pensyocr.cpp):
 * Tesseract, compiled for the phone, reading Italian text offline.
 * No Android types here, so this file can also be tested on a PC.
 */
object Tess {
    /** Load the model; `dir` holds <lang>.traineddata. Only the first call does work. */
    @JvmStatic external fun init(dir: String, lang: String): Boolean

    /** 8-bit grayscale pixels -> UTF-8 lines "left\ttop\tright\tbottom\tconfidence\ttext". */
    @JvmStatic external fun recognize(gray: ByteArray, width: Int, height: Int): ByteArray
}
