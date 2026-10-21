package app.pensy

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import app.pensy.core.Code
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/*
 * QR codes and barcodes, with ZXing (pure Java, works offline).
 * decode() finds every code in a photo; render() draws a code again
 * from its saved content, so it can be scanned at the post office or
 * by a banking app exactly like the printed one.
 */
object Codes {

    private val FORMATS = listOf(
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417, BarcodeFormat.AZTEC,
        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8, BarcodeFormat.ITF, BarcodeFormat.UPC_A, BarcodeFormat.CODABAR
    )

    private val TWO_D = setOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC)

    fun decode(src: Bitmap): List<Code> {
        val out = LinkedHashMap<String, Code>()
        val bmp = scaleDown(src, 2000)
        tryDecode(bmp, out)
        if (out.isEmpty()) {
            // 1D barcodes need the right orientation: try the photo turned by 90 degrees.
            val m = Matrix().apply { postRotate(90f) }
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            tryDecode(rot, out)
            if (rot != bmp) rot.recycle()
        }
        if (bmp != src) bmp.recycle()
        return out.values.toList()
    }

    private fun tryDecode(bmp: Bitmap, out: MutableMap<String, Code>) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, px)
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.POSSIBLE_FORMATS] = FORMATS
        val reader = GenericMultipleBarcodeReader(MultiFormatReader())
        for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            try {
                for (r in reader.decodeMultiple(BinaryBitmap(bin), hints)) {
                    val text = r.text ?: continue
                    out[r.barcodeFormat.name + "|" + text] = Code(r.barcodeFormat.name, text)
                }
            } catch (e: NotFoundException) {
                // nothing with this binarizer
            }
            if (out.isNotEmpty()) return
        }
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= max) return b
        val s = max.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    fun isTwoD(code: Code): Boolean = runCatching { BarcodeFormat.valueOf(code.format) in TWO_D }.getOrDefault(false)

    /** Draw the code. widthPx is the target width; returns null if the format can't be written. */
    fun render(code: Code, widthPx: Int): Bitmap? {
        return try {
            val fmt = BarcodeFormat.valueOf(code.format)
            val height = when {
                fmt in TWO_D -> widthPx
                fmt == BarcodeFormat.PDF_417 -> widthPx / 3
                else -> widthPx * 2 / 5
            }
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.MARGIN] = if (fmt in TWO_D) 1 else 4
            if (code.content.any { it.code > 127 }) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            if (fmt == BarcodeFormat.QR_CODE) hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            val m = MultiFormatWriter().encode(code.content, fmt, widthPx, height, hints)
            val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
            val row = IntArray(m.width)
            for (y in 0 until m.height) {
                for (x in 0 until m.width) row[x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
                bmp.setPixels(row, 0, m.width, 0, y, m.width, 1)
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
