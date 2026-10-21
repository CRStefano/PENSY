package app.pensy

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/** An image that can be pinched, panned and double-tapped; a horizontal fling asks for the next page. */
class ZoomView(ctx: Context) : ImageView(ctx) {

    var onSwipe: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val m = Matrix()
    private var scale = 1f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(1f, 6f)
            val k = ns / scale
            scale = ns
            m.postScale(k, k, d.focusX, d.focusY)
            settle()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1.01f) return false
            m.postTranslate(-dx, -dy)
            settle()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) fit() else { scale = 2.5f; m.postScale(2.5f, 2.5f, e.x, e.y); settle() }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale > 1.01f || Math.abs(vx) < Math.abs(vy) * 1.5f || Math.abs(vx) < 600) return false
            onSwipe?.invoke(if (vx < 0) 1 else -1)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    /** The whole page, centred. */
    fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0) return
        val k = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        m.reset()
        m.postScale(k, k)
        m.postTranslate((width - d.intrinsicWidth * k) / 2f, (height - d.intrinsicHeight * k) / 2f)
        scale = 1f
        imageMatrix = m
    }

    /** Keep the page on screen: centred when smaller than the view, edges never inside it when larger. */
    private fun settle() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}

/**
 * Pages shown full screen, one at a time: swipe for the next, pinch to
 * read the small print. `onShare` and `onDelete` add their buttons.
 */
object Viewer {

    fun show(
        act: MainActivity, files: List<File>, start: Int, title: String,
        onShare: ((Int) -> Unit)? = null, onDelete: ((Int) -> Unit)? = null
    ) {
        if (files.isEmpty()) return
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var i = start.coerceIn(0, files.size - 1)
        val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val img = ZoomView(act)
        root.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = act.hbox().apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(act.dp(6), act.dp(28), act.dp(6), act.dp(6))
        }
        val white = Color.WHITE
        bar.add(act.iconButton(R.drawable.ic_back, R.string.back, white) { d.dismiss() }, w = WRAP)
        val counter = act.text("", 16f, white, MEDIUM, lines = 1)
        bar.add(counter, w = 0, weight = 1f, start = 4)
        if (onShare != null) bar.add(act.iconButton(R.drawable.ic_share, R.string.share, white) { onShare(i) }, w = WRAP)
        if (onDelete != null) bar.add(act.iconButton(R.drawable.ic_delete, R.string.delete, white) { d.dismiss(); onDelete(i) }, w = WRAP)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        fun showPage() {
            val side = maxOf(act.resources.displayMetrics.widthPixels, act.resources.displayMetrics.heightPixels) * 2
            img.setImageBitmap(Pages.loadFile(files[i], side))
            img.fit()
            counter.text = if (files.size > 1) "$title · ${i + 1}/${files.size}" else title
        }
        img.onSwipe = { dir ->
            val n = i + dir
            if (n in files.indices) { i = n; showPage() }
        }
        img.onTap = { bar.visibility = if (bar.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        d.show()
        img.post { showPage() }
    }
}
