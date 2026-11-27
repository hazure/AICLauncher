package com.aliceincradle.launcher.ui

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Xfermode
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertTrue
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBitmap
import org.robolectric.shadows.ShadowLegacyBitmap
import org.robolectric.shadows.ShadowLegacyCanvas
import org.robolectric.shadows.ShadowLegacyPath
import org.robolectric.shadows.ShadowPaint
import org.robolectric.util.ReflectionHelpers

/**
 * Pure JVM software-rendering shadows and screenshot capture helpers for Arm64/Linux environments
 * where Robolectric native graphics/conscrypt binaries are unavailable.
 */
@Implements(value = Bitmap::class, isInAndroidSdk = true)
class Arm64BitmapShadow : ShadowLegacyBitmap() {
    companion object {
        @JvmStatic
        @Implementation(minSdk = Build.VERSION_CODES.O)
        fun createBitmap(
            displayMetrics: DisplayMetrics?,
            width: Int,
            height: Int,
            config: Bitmap.Config,
            hasAlpha: Boolean,
            colorSpace: ColorSpace?
        ): Bitmap {
            return if (colorSpace != null || config == Bitmap.Config.ALPHA_8) {
                ShadowLegacyBitmap.createBitmap(width, height, config, hasAlpha, colorSpace)
            } else {
                ShadowLegacyBitmap.createBitmap(displayMetrics, width, height, config, hasAlpha)
            }
        }
    }
}

/**
 * Keeps [Paint] color, alpha, style, stroke caps/joins, and text size synchronized with Android
 * semantics instead of Robolectric [ShadowPaint]'s uninitialized zero defaults.
 */
@Implements(value = Paint::class, isInAndroidSdk = true)
class Arm64PaintShadow : ShadowPaint() {

    private var colorInitialized = false
    private var alphaInitialized = false
    private var blendMode: BlendMode? = null
    private var xfermode: Xfermode? = null

    @Implementation
    fun __constructor__() {
        ensureDefaults()
    }

    @Implementation
    override fun __constructor__(paint: Paint) {
        super.__constructor__(paint)
        colorInitialized = true
        alphaInitialized = true
        val otherShadow = runCatching { Shadow.extract<Any>(paint) }.getOrNull() as? Arm64PaintShadow
        if (otherShadow != null) {
            blendMode = otherShadow.blendMode
            xfermode = otherShadow.xfermode
        }
    }

    @Implementation
    override fun setColor(color: Int) {
        super.setColor(color)
        super.setAlpha((color ushr 24) and 0xFF)
        colorInitialized = true
        alphaInitialized = true
    }

    @Implementation
    override fun setAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 255)
        ensureDefaults()
        super.setAlpha(clamped)
        val rgb = super.getColor() and 0x00FFFFFF
        super.setColor(rgb or (clamped shl 24))
        alphaInitialized = true
    }

    @Implementation
    override fun getColor(): Int {
        ensureDefaults()
        return super.getColor()
    }

    @Implementation
    override fun getAlpha(): Int {
        ensureDefaults()
        return super.getAlpha()
    }

    @Implementation
    fun setBlendMode(blendMode: BlendMode?) {
        this.blendMode = blendMode
        this.xfermode = null
    }

    @Implementation
    fun getBlendMode(): BlendMode? = blendMode

    @Implementation
    fun setXfermode(xfermode: Xfermode?): Xfermode? {
        this.xfermode = xfermode
        return xfermode
    }

    @Implementation
    fun getXfermode(): Xfermode? = xfermode

    internal fun activeBlendMode(): BlendMode? = blendMode

    @Implementation
    override fun measureText(text: String): Float =
        BitmapFontMetrics.measure(text, 0, text.length, effectiveTextSize(), super.getTextScaleX())

    @Implementation
    override fun measureText(text: String, start: Int, end: Int): Float =
        BitmapFontMetrics.measure(text, start, end, effectiveTextSize(), super.getTextScaleX())

    @Implementation
    override fun measureText(text: CharSequence, start: Int, end: Int): Float =
        BitmapFontMetrics.measure(text, start, end, effectiveTextSize(), super.getTextScaleX())

    @Implementation
    override fun measureText(text: CharArray, index: Int, count: Int): Float =
        BitmapFontMetrics.measure(text, index, index + count, effectiveTextSize(), super.getTextScaleX())

    @Implementation
    fun getTextWidths(text: String, widths: FloatArray): Int =
        fillAdvances(text, 0, text.length, widths, 0)

    @Implementation
    fun getTextWidths(text: String, start: Int, end: Int, widths: FloatArray): Int =
        fillAdvances(text, start, end, widths, 0)

    @Implementation
    fun getTextWidths(text: CharSequence, start: Int, end: Int, widths: FloatArray): Int =
        fillAdvances(text, start, end, widths, 0)

    @Implementation
    fun getTextWidths(text: CharArray, index: Int, count: Int, widths: FloatArray): Int =
        fillCharArrayAdvances(text, index, index + count, widths, 0)

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getTextRunAdvances(
        chars: CharArray,
        index: Int,
        count: Int,
        contextIndex: Int,
        contextCount: Int,
        isRtl: Boolean,
        advances: FloatArray?,
        advancesIndex: Int
    ): Float {
        if (advances != null) {
            fillCharArrayAdvances(chars, index, index + count, advances, advancesIndex)
        }
        return BitmapFontMetrics.measure(chars, index, index + count, effectiveTextSize(), super.getTextScaleX())
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getRunAdvance(
        text: CharArray,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        isRtl: Boolean,
        offset: Int
    ): Float {
        val clampedEnd = offset.coerceIn(start, end)
        return BitmapFontMetrics.measure(text, start, clampedEnd, effectiveTextSize(), super.getTextScaleX())
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getRunAdvance(
        text: CharSequence,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        isRtl: Boolean,
        offset: Int
    ): Float {
        val clampedEnd = offset.coerceIn(start, end)
        return BitmapFontMetrics.measure(text, start, clampedEnd, effectiveTextSize(), super.getTextScaleX())
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getRunCharacterAdvance(
        text: CharArray,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        isRtl: Boolean,
        offset: Int,
        advances: FloatArray?,
        advancesIndex: Int
    ): Float {
        if (advances != null) {
            fillCharArrayAdvances(text, start, end, advances, advancesIndex)
        }
        val clampedEnd = offset.coerceIn(start, end)
        return BitmapFontMetrics.measure(text, start, clampedEnd, effectiveTextSize(), super.getTextScaleX())
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getRunCharacterAdvance(
        text: CharSequence,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        isRtl: Boolean,
        offset: Int,
        advances: FloatArray?,
        advancesIndex: Int
    ): Float {
        if (advances != null) {
            fillAdvances(text, start, end, advances, advancesIndex)
        }
        val clampedEnd = offset.coerceIn(start, end)
        return BitmapFontMetrics.measure(text, start, clampedEnd, effectiveTextSize(), super.getTextScaleX())
    }

    @Implementation
    fun getFontMetrics(metrics: Paint.FontMetrics?): Float {
        val ts = effectiveTextSize()
        val target = metrics ?: Paint.FontMetrics()
        target.top = -ts * 1.05f
        target.ascent = -ts * 0.90f
        target.descent = ts * 0.25f
        target.bottom = ts * 0.30f
        target.leading = 0f
        return target.descent - target.ascent
    }

    @Implementation
    fun getFontMetrics(): Paint.FontMetrics = Paint.FontMetrics().also { getFontMetrics(it) }

    @Implementation
    override fun getFontMetricsInt(fmi: Paint.FontMetricsInt?): Int {
        val ts = effectiveTextSize()
        val target = fmi ?: Paint.FontMetricsInt()
        target.top = floor(-ts * 1.05f).toInt()
        target.ascent = floor(-ts * 0.90f).toInt()
        target.descent = ceil(ts * 0.25f).toInt()
        target.bottom = ceil(ts * 0.30f).toInt()
        target.leading = 0
        return target.descent - target.ascent
    }

    @Implementation
    fun getFontMetricsInt(): Paint.FontMetricsInt = Paint.FontMetricsInt().also { getFontMetricsInt(it) }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getFontMetricsInt(
        text: CharSequence,
        start: Int,
        count: Int,
        contextStart: Int,
        contextCount: Int,
        isRtl: Boolean,
        outMetrics: Paint.FontMetricsInt
    ) {
        getFontMetricsInt(outMetrics)
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun getFontMetricsInt(
        text: CharArray,
        start: Int,
        count: Int,
        contextStart: Int,
        contextCount: Int,
        isRtl: Boolean,
        outMetrics: Paint.FontMetricsInt
    ) {
        getFontMetricsInt(outMetrics)
    }

    private fun fillAdvances(text: CharSequence, start: Int, end: Int, out: FloatArray, outIndex: Int): Int {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val ts = effectiveTextSize()
        val sx = max(0f, super.getTextScaleX())
        val count = min(e - s, max(0, out.size - outIndex))
        for (i in 0 until count) {
            out[outIndex + i] = BitmapFontMetrics.charAdvance(text[s + i], ts) * sx
        }
        return count
    }

    private fun fillCharArrayAdvances(text: CharArray, start: Int, end: Int, out: FloatArray, outIndex: Int): Int {
        val s = start.coerceIn(0, text.size)
        val e = end.coerceIn(s, text.size)
        val ts = effectiveTextSize()
        val sx = max(0f, super.getTextScaleX())
        val count = min(e - s, max(0, out.size - outIndex))
        for (i in 0 until count) {
            out[outIndex + i] = BitmapFontMetrics.charAdvance(text[s + i], ts) * sx
        }
        return count
    }

    private fun effectiveTextSize(): Float {
        ensureDefaults()
        return max(8f, super.getTextSize())
    }

    private fun ensureDefaults() {
        if (!colorInitialized) {
            super.setColor(Color.BLACK)
            colorInitialized = true
        }
        if (!alphaInitialized) {
            super.setAlpha(255)
            alphaInitialized = true
        }
        if (super.getStyle() == null) {
            super.setStyle(Paint.Style.FILL)
        }
        if (super.getCap() == null) {
            super.setStrokeCap(Paint.Cap.BUTT)
        }
        if (super.getJoin() == null) {
            super.setStrokeJoin(Paint.Join.MITER)
        }
        if (super.getTextSize() <= 0f) {
            super.setTextSize(16f)
        }
    }
}

/**
 * Pure Kotlin software rasterizer overriding [ShadowLegacyCanvas] to handle Compose API 21+ float
 * drawing overloads, 2D matrix/clip stacks, offscreen [saveLayer], vector [Path]s, and text runs.
 */
@Implements(value = android.graphics.Canvas::class, isInAndroidSdk = true)
class Arm64CanvasShadow : ShadowLegacyCanvas() {

    private data class SavedCanvasState(
        val matrix: AffineMatrix2D,
        val clip: PixelClip?,
        val offscreenPixels: IntArray?,
        val layerAlpha: Int,
        val parentPixels: IntArray?
    )

    private var boundBitmap: Bitmap? = null
    private var matrix = AffineMatrix2D.identity()
    private var clip: PixelClip? = null
    private var activeOffscreenPixels: IntArray? = null
    private val stateStack = ArrayDeque<SavedCanvasState>()

    @Implementation
    fun __constructor__() {
        resetState(null)
    }

    @Implementation
    override fun __constructor__(bitmap: Bitmap) {
        super.__constructor__(bitmap)
        resetState(bitmap)
    }

    @Implementation
    override fun setBitmap(bitmap: Bitmap?) {
        super.setBitmap(bitmap)
        resetState(bitmap)
    }

    private fun resetState(bitmap: Bitmap?) {
        boundBitmap = bitmap
        matrix = AffineMatrix2D.identity()
        clip = null
        activeOffscreenPixels = null
        stateStack.clear()
    }

    // --- State & Layer Stack ---

    @Implementation
    override fun save(): Int {
        val prevCount = getSaveCount()
        stateStack.addLast(
            SavedCanvasState(
                matrix = matrix.copy(),
                clip = clip?.copy(),
                offscreenPixels = null,
                layerAlpha = 255,
                parentPixels = activeOffscreenPixels
            )
        )
        super.save()
        return prevCount
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun saveLayer(bounds: RectF?, paint: Paint?, saveFlags: Int): Int {
        return pushLayer(bounds?.left, bounds?.top, bounds?.right, bounds?.bottom, effectiveAlphaByte(paint, paint?.color ?: Color.BLACK))
    }

    @Implementation
    fun saveLayer(bounds: RectF?, paint: Paint?): Int =
        saveLayer(bounds, paint, android.graphics.Canvas.ALL_SAVE_FLAG)

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?, saveFlags: Int): Int {
        return pushLayer(left, top, right, bottom, effectiveAlphaByte(paint, paint?.color ?: Color.BLACK))
    }

    @Implementation
    fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?): Int =
        saveLayer(left, top, right, bottom, paint, android.graphics.Canvas.ALL_SAVE_FLAG)

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun saveLayerAlpha(bounds: RectF?, alpha: Int, saveFlags: Int): Int {
        return pushLayer(bounds?.left, bounds?.top, bounds?.right, bounds?.bottom, alpha.coerceIn(0, 255))
    }

    @Implementation
    fun saveLayerAlpha(bounds: RectF?, alpha: Int): Int =
        saveLayerAlpha(bounds, alpha, android.graphics.Canvas.ALL_SAVE_FLAG)

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int, saveFlags: Int): Int {
        return pushLayer(left, top, right, bottom, alpha.coerceIn(0, 255))
    }

    @Implementation
    fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int): Int =
        saveLayerAlpha(left, top, right, bottom, alpha, android.graphics.Canvas.ALL_SAVE_FLAG)

    private fun pushLayer(left: Float?, top: Float?, right: Float?, bottom: Float?, alpha: Int): Int {
        val prevCount = getSaveCount()
        val surface = resolveSurface()
        val offscreen = if (surface != null && surface.width > 0 && surface.height > 0) {
            IntArray(surface.width * surface.height)
        } else {
            null
        }
        stateStack.addLast(
            SavedCanvasState(
                matrix = matrix.copy(),
                clip = clip?.copy(),
                offscreenPixels = offscreen,
                layerAlpha = alpha.coerceIn(0, 255),
                parentPixels = activeOffscreenPixels
            )
        )
        if (offscreen != null) {
            activeOffscreenPixels = offscreen
        }
        if (left != null && top != null && right != null && bottom != null && right > left && bottom > top) {
            clipRect(left, top, right, bottom, Region.Op.INTERSECT)
        }
        super.save()
        return prevCount
    }

    @Implementation
    override fun restore() {
        val saved = stateStack.removeLastOrNull()
        if (saved != null) {
            val layerBuf = saved.offscreenPixels
            activeOffscreenPixels = saved.parentPixels
            matrix = saved.matrix
            clip = saved.clip
            if (layerBuf != null && saved.layerAlpha > 0) {
                val surface = resolveSurface()
                if (surface != null) {
                    compositeLayerOntoSurface(surface, layerBuf, saved.layerAlpha, clip)
                }
            }
        }
        super.restore()
    }

    @Implementation
    override fun restoreToCount(saveCount: Int) {
        val target = max(1, saveCount)
        while (getSaveCount() > target && stateStack.isNotEmpty()) {
            restore()
        }
        super.restoreToCount(target)
    }

    @Implementation
    override fun getSaveCount(): Int = stateStack.size + 1

    // --- Transforms ---

    @Implementation
    override fun translate(dx: Float, dy: Float) {
        matrix.preTranslate(dx, dy)
    }

    @Implementation
    override fun scale(sx: Float, sy: Float) {
        matrix.preScale(sx, sy)
    }

    @Implementation
    override fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        matrix.preTranslate(px, py)
        matrix.preScale(sx, sy)
        matrix.preTranslate(-px, -py)
    }

    @Implementation
    fun rotate(degrees: Float) {
        matrix.preRotate(degrees)
    }

    @Implementation
    fun rotate(degrees: Float, px: Float, py: Float) {
        matrix.preTranslate(px, py)
        matrix.preRotate(degrees)
        matrix.preTranslate(-px, -py)
    }

    @Implementation
    fun skew(sx: Float, sy: Float) {
        matrix.preConcat(AffineMatrix2D(1f, sy, sx, 1f, 0f, 0f))
    }

    @Implementation
    fun concat(m: Matrix?) {
        if (m == null) return
        matrix.preConcat(AffineMatrix2D.fromAndroidMatrix(m))
    }

    @Implementation
    fun setMatrix(m: Matrix?) {
        matrix = if (m == null) AffineMatrix2D.identity() else AffineMatrix2D.fromAndroidMatrix(m)
    }

    @Implementation
    fun getMatrix(ctm: Matrix) {
        matrix.writeToAndroidMatrix(ctm)
    }

    // --- Clipping ---

    @Implementation
    fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        clipRect(left, top, right, bottom, Region.Op.INTERSECT)

    @Implementation
    fun clipRect(left: Float, top: Float, right: Float, bottom: Float, op: Region.Op): Boolean {
        val surface = resolveSurface() ?: return false
        val current = clip ?: PixelClip.full(surface.width, surface.height)
        val x0 = min(left, right)
        val y0 = min(top, bottom)
        val x1 = max(left, right)
        val y1 = max(top, bottom)
        clip = if (matrix.isRectPreserving) {
            val tlX = matrix.mapX(x0, y0)
            val tlY = matrix.mapY(x0, y0)
            val brX = matrix.mapX(x1, y1)
            val brY = matrix.mapY(x1, y1)
            val dl = min(tlX, brX)
            val dt = min(tlY, brY)
            val dr = max(tlX, brX)
            val db = max(tlY, brY)
            current.applyDeviceRect(dl, dt, dr, db, op, surface.width, surface.height)
        } else {
            val poly = listOf(
                floatArrayOf(matrix.mapX(x0, y0), matrix.mapY(x0, y0)),
                floatArrayOf(matrix.mapX(x1, y0), matrix.mapY(x1, y0)),
                floatArrayOf(matrix.mapX(x1, y1), matrix.mapY(x1, y1)),
                floatArrayOf(matrix.mapX(x0, y1), matrix.mapY(x0, y1))
            )
            current.applyPolygonMask(listOf(PathContour(poly, closed = true)), evenOdd = false, op, surface.width, surface.height)
        }
        return clip?.isEmpty == false
    }

    @Implementation
    fun clipRect(rect: RectF): Boolean = clipRect(rect.left, rect.top, rect.right, rect.bottom)

    @Implementation
    fun clipRect(rect: RectF, op: Region.Op): Boolean = clipRect(rect.left, rect.top, rect.right, rect.bottom, op)

    @Implementation
    fun clipRect(rect: Rect): Boolean =
        clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())

    @Implementation
    fun clipRect(rect: Rect, op: Region.Op): Boolean =
        clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), op)

    @Implementation
    fun clipRect(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    @Implementation
    fun clipOutRect(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        clipRect(left, top, right, bottom, Region.Op.DIFFERENCE)

    @Implementation
    fun clipOutRect(rect: RectF): Boolean = clipOutRect(rect.left, rect.top, rect.right, rect.bottom)

    @Implementation
    fun clipOutRect(rect: Rect): Boolean =
        clipOutRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())

    @Implementation
    fun clipOutRect(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        clipOutRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    @Implementation
    fun clipPath(path: Path): Boolean = clipPath(path, Region.Op.INTERSECT)

    @Implementation
    fun clipPath(path: Path, op: Region.Op): Boolean {
        val surface = resolveSurface() ?: return false
        val contours = extractPathContours(path, matrix)
        if (contours.isEmpty()) return clip?.isEmpty == false
        val current = clip ?: PixelClip.full(surface.width, surface.height)
        val evenOdd = path.fillType == Path.FillType.EVEN_ODD || path.fillType == Path.FillType.INVERSE_EVEN_ODD
        clip = current.applyPolygonMask(contours, evenOdd, op, surface.width, surface.height)
        return clip?.isEmpty == false
    }

    @Implementation
    fun clipOutPath(path: Path): Boolean = clipPath(path, Region.Op.DIFFERENCE)

    @Implementation
    override fun getClipBounds(bounds: Rect): Boolean {
        val surface = resolveSurface() ?: return super.getClipBounds(bounds)
        val activeClip = clip ?: PixelClip.full(surface.width, surface.height)
        if (activeClip.isEmpty) {
            bounds.setEmpty()
            return false
        }
        val inv = matrix.invert()
        if (inv == null) {
            bounds.set(activeClip.left, activeClip.top, activeClip.right, activeClip.bottom)
            return !bounds.isEmpty
        }
        val l = activeClip.left.toFloat()
        val t = activeClip.top.toFloat()
        val r = activeClip.right.toFloat()
        val b = activeClip.bottom.toFloat()
        val xs = floatArrayOf(inv.mapX(l, t), inv.mapX(r, t), inv.mapX(r, b), inv.mapX(l, b))
        val ys = floatArrayOf(inv.mapY(l, t), inv.mapY(r, t), inv.mapY(r, b), inv.mapY(l, b))
        bounds.set(
            floor(xs.min()).toInt(),
            floor(ys.min()).toInt(),
            ceil(xs.max()).toInt(),
            ceil(ys.max()).toInt()
        )
        return !bounds.isEmpty
    }

    // --- Drawing Primitives ---

    @Implementation
    override fun drawColor(color: Int) {
        fillDeviceBackground(color)
    }

    @Implementation
    fun drawColor(color: Long) {
        fillDeviceBackground(Color.toArgb(color))
    }

    @Implementation
    fun drawColor(color: Int, mode: PorterDuff.Mode) {
        if (mode == PorterDuff.Mode.CLEAR) {
            clearDeviceBackground()
        } else {
            fillDeviceBackground(color, mode == PorterDuff.Mode.SRC)
        }
    }

    @Implementation
    fun drawColor(color: Int, mode: BlendMode) {
        if (mode == BlendMode.CLEAR) {
            clearDeviceBackground()
        } else {
            fillDeviceBackground(color, mode == BlendMode.SRC)
        }
    }

    @Implementation
    override fun drawPaint(paint: Paint) {
        val surface = resolveSurface() ?: return
        val mode = resolveBlendMode(paint)
        if (mode == BlendMode.CLEAR) {
            clearRegion(surface, 0, 0, surface.width, surface.height)
            return
        }
        val argb = effectiveColorArgb(paint) ?: return
        fillRegion(surface, 0, 0, surface.width, surface.height, argb, mode)
    }

    @Implementation
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        val x0 = min(left, right)
        val y0 = min(top, bottom)
        val x1 = max(left, right)
        val y1 = max(top, bottom)
        val hw = (x1 - x0) * 0.5f
        val hh = (y1 - y0) * 0.5f
        if (hw <= 0f || hh <= 0f) return
        val style = paint.style ?: Paint.Style.FILL
        val mode = resolveBlendMode(paint)
        if (matrix.isRectPreserving && style == Paint.Style.FILL && (mode == null || mode == BlendMode.SRC_OVER || mode == BlendMode.SRC)) {
            val sx0 = matrix.mapX(x0, y0)
            val sy0 = matrix.mapY(x0, y0)
            val sx1 = matrix.mapX(x1, y1)
            val sy1 = matrix.mapY(x1, y1)
            fillRegion(
                surface,
                min(sx0, sx1).roundToInt(),
                min(sy0, sy1).roundToInt(),
                max(sx0, sx1).roundToInt(),
                max(sy0, sy1).roundToInt(),
                argb,
                mode ?: BlendMode.SRC_OVER
            )
            return
        }
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        rasterizeSdf(surface, x0, y0, x1, y1, argb, paint) { lx, ly ->
            val dx = abs(lx - cx) - hw
            val dy = abs(ly - cy) - hh
            hypot(max(dx, 0f), max(dy, 0f)) + min(max(dx, dy), 0f)
        }
    }

    @Implementation
    fun drawRect(rect: RectF, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

    @Implementation
    override fun drawRect(rect: Rect, paint: Paint) =
        drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)

    @Implementation
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        val x0 = min(left, right)
        val y0 = min(top, bottom)
        val x1 = max(left, right)
        val y1 = max(top, bottom)
        val hw = (x1 - x0) * 0.5f
        val hh = (y1 - y0) * 0.5f
        if (hw <= 0f || hh <= 0f) return
        val radius = max(0f, min(min(rx, ry), min(hw, hh)))
        if (radius <= 0.25f) {
            drawRect(x0, y0, x1, y1, paint)
            return
        }
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        rasterizeSdf(surface, x0, y0, x1, y1, argb, paint) { lx, ly ->
            val qx = abs(lx - cx) - (hw - radius)
            val qy = abs(ly - cy) - (hh - radius)
            min(max(qx, qy), 0f) + hypot(max(qx, 0f), max(qy, 0f)) - radius
        }
    }

    @Implementation
    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) =
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint)

    @Implementation
    fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        val x0 = min(left, right)
        val y0 = min(top, bottom)
        val x1 = max(left, right)
        val y1 = max(top, bottom)
        val rx = (x1 - x0) * 0.5f
        val ry = (y1 - y0) * 0.5f
        if (rx <= 0f || ry <= 0f) return
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        val avgR = (rx + ry) * 0.5f
        rasterizeSdf(surface, x0, y0, x1, y1, argb, paint) { lx, ly ->
            val nx = (lx - cx) / rx
            val ny = (ly - cy) / ry
            (hypot(nx, ny) - 1f) * avgR
        }
    }

    @Implementation
    override fun drawOval(oval: RectF, paint: Paint) =
        drawOval(oval.left, oval.top, oval.right, oval.bottom, paint)

    @Implementation
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        if (radius <= 0f) return
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        rasterizeSdf(surface, cx - radius, cy - radius, cx + radius, cy + radius, argb, paint) { lx, ly ->
            hypot(lx - cx, ly - cy) - radius
        }
    }

    @Implementation
    fun drawArc(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        startAngle: Float,
        sweepAngle: Float,
        useCenter: Boolean,
        paint: Paint
    ) {
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        val x0 = min(left, right)
        val y0 = min(top, bottom)
        val x1 = max(left, right)
        val y1 = max(top, bottom)
        val rx = (x1 - x0) * 0.5f
        val ry = (y1 - y0) * 0.5f
        if (rx <= 0f || ry <= 0f || sweepAngle == 0f) return
        if (abs(sweepAngle) >= 360f && !useCenter) {
            drawOval(x0, y0, x1, y1, paint)
            return
        }

        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        val avgR = (rx + ry) * 0.5f
        val style = paint.style ?: Paint.Style.FILL
        val halfStroke = max(1f, paint.strokeWidth) * 0.5f
        val roundCap = paint.strokeCap == Paint.Cap.ROUND

        if (style == Paint.Style.STROKE && !useCenter) {
            val pad = halfStroke + 2f
            val startRad = normalizeAngleRad(startAngle)
            val sweepAbs = min(360f, abs(sweepAngle))
            val sweepRad = (sweepAbs * PI / 180.0).toFloat()
            val normStartRad = if (sweepAngle >= 0f) startRad else normalizeAngleRad(startAngle + sweepAngle)
            val endRad = normStartRad + sweepRad
            val sPtX = cx + rx * cos(normStartRad)
            val sPtY = cy + ry * sin(normStartRad)
            val ePtX = cx + rx * cos(endRad)
            val ePtY = cy + ry * sin(endRad)

            rasterizeCustomSdf(surface, x0 - pad, y0 - pad, x1 + pad, y1 + pad, argb) { lx, ly ->
                var angle = atan2(ly - cy, lx - cx)
                if (angle < normStartRad) angle += (2.0 * PI).toFloat()
                if (angle < normStartRad) angle += (2.0 * PI).toFloat()
                val inSweep = (angle - normStartRad) <= sweepRad
                if (inSweep) {
                    val radialDist = abs(hypot((lx - cx) / rx, (ly - cy) / ry) - 1f) * avgR
                    radialDist - halfStroke
                } else if (roundCap) {
                    val d1 = hypot(lx - sPtX, ly - sPtY)
                    val d2 = hypot(lx - ePtX, ly - ePtY)
                    min(d1, d2) - halfStroke
                } else {
                    val midGap = normStartRad + sweepRad + ((2f * PI.toFloat() - sweepRad) * 0.5f)
                    val capDist = if (angle <= midGap) hypot(lx - sPtX, ly - sPtY) else hypot(lx - ePtX, ly - ePtY)
                    max(capDist, 0.5f)
                }
            }
        } else {
            val normStartRad = if (sweepAngle >= 0f) normalizeAngleRad(startAngle) else normalizeAngleRad(startAngle + sweepAngle)
            val sweepRad = (min(360f, abs(sweepAngle)) * PI / 180.0).toFloat()
            rasterizeSdf(surface, x0, y0, x1, y1, argb, paint) { lx, ly ->
                var angle = atan2(ly - cy, lx - cx)
                if (angle < normStartRad) angle += (2.0 * PI).toFloat()
                val radial = (hypot((lx - cx) / rx, (ly - cy) / ry) - 1f) * avgR
                if ((angle - normStartRad) <= sweepRad) radial else 10f
            }
        }
    }

    @Implementation
    override fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) =
        drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint)

    @Implementation
    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        val halfStroke = max(0.75f, paint.strokeWidth * 0.5f)
        val pad = halfStroke + 2f
        val dx = stopX - startX
        val dy = stopY - startY
        val lenSq = dx * dx + dy * dy
        rasterizeCustomSdf(
            surface,
            min(startX, stopX) - pad,
            min(startY, stopY) - pad,
            max(startX, stopX) + pad,
            max(startY, stopY) + pad,
            argb
        ) { lx, ly ->
            if (lenSq <= 1e-6f) {
                hypot(lx - startX, ly - startY) - halfStroke
            } else {
                val t = (((lx - startX) * dx + (ly - startY) * dy) / lenSq).coerceIn(0f, 1f)
                hypot(lx - (startX + t * dx), ly - (startY + t * dy)) - halfStroke
            }
        }
    }

    @Implementation
    fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        var i = offset
        val limit = min(pts.size - 3, offset + count - 3)
        while (i <= limit) {
            drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], paint)
            i += 4
        }
    }

    @Implementation
    fun drawLines(pts: FloatArray, paint: Paint) = drawLines(pts, 0, pts.size, paint)

    @Implementation
    fun drawPoint(x: Float, y: Float, paint: Paint) =
        drawCircle(x, y, max(0.75f, paint.strokeWidth * 0.5f), paint)

    @Implementation
    override fun drawPath(path: Path, paint: Paint) {
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        val contours = extractPathContours(path, matrix)
        if (contours.isEmpty()) return

        val style = paint.style ?: Paint.Style.FILL
        val evenOdd = path.fillType == Path.FillType.EVEN_ODD || path.fillType == Path.FillType.INVERSE_EVEN_ODD

        if (style == Paint.Style.FILL || style == Paint.Style.FILL_AND_STROKE) {
            fillPolygonContours(surface, contours, evenOdd, argb, clip)
        }
        if (style == Paint.Style.STROKE || style == Paint.Style.FILL_AND_STROKE) {
            val savedMatrix = matrix
            matrix = AffineMatrix2D.identity()
            for (contour in contours) {
                val pts = contour.points
                if (pts.size < 2) continue
                for (i in 0 until pts.size - 1) {
                    drawLine(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], paint)
                }
                if (contour.closed && pts.size >= 2) {
                    drawLine(pts.last()[0], pts.last()[1], pts.first()[0], pts.first()[1], paint)
                }
            }
            matrix = savedMatrix
        }
    }

    @Implementation
    override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        blitBitmap(bitmap, null, RectF(left, top, left + bitmap.width, top + bitmap.height), paint)
    }

    @Implementation
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
        blitBitmap(bitmap, src, RectF(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat()), paint)
    }

    @Implementation
    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        blitBitmap(bitmap, src, dst, paint)
    }

    @Implementation
    override fun drawBitmap(bitmap: Bitmap, m: Matrix, paint: Paint?) {
        val saved = matrix.copy()
        matrix.preConcat(AffineMatrix2D.fromAndroidMatrix(m))
        drawBitmap(bitmap, 0f, 0f, paint)
        matrix = saved
    }

    // --- Text Drawing ---

    @Implementation
    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        renderText(text, x, y, paint)
    }

    @Implementation
    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        if (start < 0 || end > text.length || start >= end) return
        renderText(text.substring(start, end), x, y, paint)
    }

    @Implementation
    override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
        if (start < 0 || end > text.length || start >= end) return
        renderText(text.subSequence(start, end).toString(), x, y, paint)
    }

    @Implementation
    override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
        if (index < 0 || count <= 0 || index + count > text.size) return
        renderText(String(text, index, count), x, y, paint)
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun drawTextRun(
        text: CharArray,
        index: Int,
        count: Int,
        contextIndex: Int,
        contextCount: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint
    ) {
        if (index < 0 || count <= 0 || index + count > text.size) return
        renderText(String(text, index, count), x, y, paint)
    }

    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun drawTextRun(
        text: CharSequence,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint
    ) {
        if (start < 0 || end > text.length || start >= end) return
        renderText(text.subSequence(start, end).toString(), x, y, paint)
    }

    private fun renderText(text: String, x: Float, baselineY: Float, paint: Paint) {
        if (text.isEmpty()) return
        val surface = resolveSurface() ?: return
        val argb = effectiveColorArgb(paint) ?: return
        val textSize = max(8f, paint.textSize)
        val align = paint.textAlign ?: Paint.Align.LEFT
        BitmapGlyphRenderer.drawString(surface, matrix, clip, text, x, baselineY, textSize, align, argb)
    }

    // --- Surface & Pixel Pipeline ---

    private fun resolveTargetBitmap(): Bitmap? =
        boundBitmap ?: ReflectionHelpers.getField<Bitmap?>(this, "targetBitmap")

    private fun resolveSurface(): RasterSurface? {
        val bmp = resolveTargetBitmap() ?: return null
        if (bmp.width <= 0 || bmp.height <= 0) return null
        val backing = SurfaceRegistry.forBitmap(bmp)
        return RasterSurface(
            bitmap = bmp,
            pixels = activeOffscreenPixels ?: backing.pixels,
            width = backing.width,
            height = backing.height,
            syncToBitmap = activeOffscreenPixels == null
        )
    }

    private fun clearDeviceBackground() {
        val surface = resolveSurface() ?: return
        clearRegion(surface, 0, 0, surface.width, surface.height)
    }

    private fun clearRegion(surface: RasterSurface, x0: Int, y0: Int, x1: Int, y1: Int) {
        val activeClip = clip
        val minX = max(x0, activeClip?.left ?: 0).coerceIn(0, surface.width)
        val maxX = min(x1, activeClip?.right ?: surface.width).coerceIn(0, surface.width)
        val minY = max(y0, activeClip?.top ?: 0).coerceIn(0, surface.height)
        val maxY = min(y1, activeClip?.bottom ?: surface.height).coerceIn(0, surface.height)
        if (minX >= maxX || minY >= maxY) return

        for (py in minY until maxY) {
            val rowOff = py * surface.width
            for (px in minX until maxX) {
                if (activeClip == null || activeClip.contains(px, py)) {
                    surface.pixels[rowOff + px] = 0
                }
            }
        }
        surface.syncRect(minX, minY, maxX, maxY)
    }

    private fun fillDeviceBackground(argb: Int, replaceSrc: Boolean = false) {
        val surface = resolveSurface() ?: return
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0 && !replaceSrc) return
        fillRegion(surface, 0, 0, surface.width, surface.height, argb, if (replaceSrc) BlendMode.SRC else BlendMode.SRC_OVER)
    }

    private fun fillRegion(
        surface: RasterSurface,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        argb: Int,
        mode: BlendMode? = BlendMode.SRC_OVER
    ) {
        val activeClip = clip
        val minX = max(x0, activeClip?.left ?: 0).coerceIn(0, surface.width)
        val maxX = min(x1, activeClip?.right ?: surface.width).coerceIn(0, surface.width)
        val minY = max(y0, activeClip?.top ?: 0).coerceIn(0, surface.height)
        val maxY = min(y1, activeClip?.bottom ?: surface.height).coerceIn(0, surface.height)
        if (minX >= maxX || minY >= maxY) return

        val opaqueFastPath = (activeClip?.hasMask != true) &&
            (mode == null || mode == BlendMode.SRC || (mode == BlendMode.SRC_OVER && ((argb ushr 24) and 0xFF) == 255))
        if (opaqueFastPath) {
            for (py in minY until maxY) {
                val rowOff = py * surface.width
                surface.pixels.fill(argb, rowOff + minX, rowOff + maxX)
            }
        } else {
            for (py in minY until maxY) {
                val rowOff = py * surface.width
                for (px in minX until maxX) {
                    if (activeClip == null || activeClip.contains(px, py)) {
                        surface.pixels[rowOff + px] = blendWithMode(surface.pixels[rowOff + px], argb, 1f, mode)
                    }
                }
            }
        }
        surface.syncRect(minX, minY, maxX, maxY)
    }

    private inline fun rasterizeSdf(
        surface: RasterSurface,
        localLeft: Float,
        localTop: Float,
        localRight: Float,
        localBottom: Float,
        argb: Int,
        paint: Paint?,
        crossinline sdf: (Float, Float) -> Float
    ) {
        val style = paint?.style ?: Paint.Style.FILL
        val strokeWidth = max(1f, paint?.strokeWidth ?: 1f)
        val halfStroke = strokeWidth * 0.5f
        val pad = if (style == Paint.Style.FILL) 1.5f else halfStroke + 1.5f
        val mode = resolveBlendMode(paint)

        when (style) {
            Paint.Style.FILL -> rasterizeCustomSdf(surface, localLeft - pad, localTop - pad, localRight + pad, localBottom + pad, argb, mode) { lx, ly ->
                sdf(lx, ly)
            }
            Paint.Style.STROKE -> rasterizeCustomSdf(surface, localLeft - pad, localTop - pad, localRight + pad, localBottom + pad, argb, mode) { lx, ly ->
                abs(sdf(lx, ly)) - halfStroke
            }
            Paint.Style.FILL_AND_STROKE -> rasterizeCustomSdf(surface, localLeft - pad, localTop - pad, localRight + pad, localBottom + pad, argb, mode) { lx, ly ->
                sdf(lx, ly) - halfStroke
            }
        }
    }

    private inline fun rasterizeCustomSdf(
        surface: RasterSurface,
        localLeft: Float,
        localTop: Float,
        localRight: Float,
        localBottom: Float,
        argb: Int,
        mode: BlendMode? = BlendMode.SRC_OVER,
        crossinline sdf: (Float, Float) -> Float
    ) {
        val inv = matrix.invert() ?: return
        val xs = floatArrayOf(
            matrix.mapX(localLeft, localTop),
            matrix.mapX(localRight, localTop),
            matrix.mapX(localRight, localBottom),
            matrix.mapX(localLeft, localBottom)
        )
        val ys = floatArrayOf(
            matrix.mapY(localLeft, localTop),
            matrix.mapY(localRight, localTop),
            matrix.mapY(localRight, localBottom),
            matrix.mapY(localLeft, localBottom)
        )
        val activeClip = clip
        val minX = max(floor(xs.min()).toInt(), activeClip?.left ?: 0).coerceIn(0, surface.width)
        val maxX = min(ceil(xs.max()).toInt(), activeClip?.right ?: surface.width).coerceIn(0, surface.width)
        val minY = max(floor(ys.min()).toInt(), activeClip?.top ?: 0).coerceIn(0, surface.height)
        val maxY = min(ceil(ys.max()).toInt(), activeClip?.bottom ?: surface.height).coerceIn(0, surface.height)
        if (minX >= maxX || minY >= maxY) return

        val pixelScale = max(0.25f, hypot(inv.a, inv.b))
        val baseAlpha = (argb ushr 24) and 0xFF
        val rgb = argb and 0x00FFFFFF

        for (py in minY until maxY) {
            val cy = py + 0.5f
            val rowOff = py * surface.width
            for (px in minX until maxX) {
                if (activeClip != null && !activeClip.contains(px, py)) continue
                val cx = px + 0.5f
                val lx = inv.mapX(cx, cy)
                val ly = inv.mapY(cx, cy)
                val distPx = sdf(lx, ly) / pixelScale
                val cov = (0.5f - distPx).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                if (mode == BlendMode.CLEAR) {
                    surface.pixels[rowOff + px] = blendWithMode(surface.pixels[rowOff + px], 0, cov, mode)
                } else {
                    val effAlpha = (baseAlpha * cov).roundToInt().coerceIn(0, 255)
                    if (effAlpha > 0 || mode == BlendMode.SRC) {
                        surface.pixels[rowOff + px] = blendWithMode(surface.pixels[rowOff + px], (effAlpha shl 24) or rgb, cov, mode)
                    }
                }
            }
        }
        surface.syncRect(minX, minY, maxX, maxY)
    }

    private fun blitBitmap(srcBitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        val surface = resolveSurface() ?: return
        if (dst.width() <= 0f || dst.height() <= 0f) return
        val sw = srcBitmap.width
        val sh = srcBitmap.height
        if (sw <= 0 || sh <= 0) return

        val srcPixels = extractBitmapPixels(srcBitmap) ?: return
        val sx0 = src?.left?.coerceIn(0, sw) ?: 0
        val sy0 = src?.top?.coerceIn(0, sh) ?: 0
        val sx1 = src?.right?.coerceIn(sx0, sw) ?: sw
        val sy1 = src?.bottom?.coerceIn(sy0, sh) ?: sh
        val srcW = sx1 - sx0
        val srcH = sy1 - sy0
        if (srcW <= 0 || srcH <= 0) return

        val tintColor = extractColorFilterColor(paint?.colorFilter)
        val globalAlpha = (paint?.alpha ?: 255).coerceIn(0, 255)
        if (globalAlpha == 0) return

        val inv = matrix.invert() ?: return
        val xs = floatArrayOf(
            matrix.mapX(dst.left, dst.top),
            matrix.mapX(dst.right, dst.top),
            matrix.mapX(dst.right, dst.bottom),
            matrix.mapX(dst.left, dst.bottom)
        )
        val ys = floatArrayOf(
            matrix.mapY(dst.left, dst.top),
            matrix.mapY(dst.right, dst.top),
            matrix.mapY(dst.right, dst.bottom),
            matrix.mapY(dst.left, dst.bottom)
        )
        val activeClip = clip
        val minX = max(floor(xs.min()).toInt(), activeClip?.left ?: 0).coerceIn(0, surface.width)
        val maxX = min(ceil(xs.max()).toInt(), activeClip?.right ?: surface.width).coerceIn(0, surface.width)
        val minY = max(floor(ys.min()).toInt(), activeClip?.top ?: 0).coerceIn(0, surface.height)
        val maxY = min(ceil(ys.max()).toInt(), activeClip?.bottom ?: surface.height).coerceIn(0, surface.height)
        if (minX >= maxX || minY >= maxY) return

        val dstW = dst.width()
        val dstH = dst.height()
        for (py in minY until maxY) {
            val rowOff = py * surface.width
            for (px in minX until maxX) {
                if (activeClip != null && !activeClip.contains(px, py)) continue
                val lx = inv.mapX(px + 0.5f, py + 0.5f)
                val ly = inv.mapY(px + 0.5f, py + 0.5f)
                val u = (lx - dst.left) / dstW
                val v = (ly - dst.top) / dstH
                if (u < 0f || u >= 1f || v < 0f || v >= 1f) continue
                val spx = (sx0 + (u * srcW).toInt()).coerceIn(sx0, sx1 - 1)
                val spy = (sy0 + (v * srcH).toInt()).coerceIn(sy0, sy1 - 1)
                var srcArgb = srcPixels[spy * sw + spx]
                if (tintColor != null) {
                    val sa = (srcArgb ushr 24) and 0xFF
                    if (sa == 0) continue
                    val ta = (tintColor ushr 24) and 0xFF
                    val aOut = (sa * ta * globalAlpha) / (255 * 255)
                    srcArgb = (aOut shl 24) or (tintColor and 0x00FFFFFF)
                } else if (globalAlpha < 255) {
                    val sa = (srcArgb ushr 24) and 0xFF
                    srcArgb = (((sa * globalAlpha) / 255) shl 24) or (srcArgb and 0x00FFFFFF)
                }
                if ((srcArgb ushr 24) and 0xFF > 0) {
                    surface.pixels[rowOff + px] = blendSrcOver(surface.pixels[rowOff + px], srcArgb)
                }
            }
        }
        surface.syncRect(minX, minY, maxX, maxY)
    }

    private fun compositeLayerOntoSurface(
        surface: RasterSurface,
        layerBuf: IntArray,
        layerAlpha: Int,
        activeClip: PixelClip?
    ) {
        val minX = activeClip?.left?.coerceIn(0, surface.width) ?: 0
        val maxX = activeClip?.right?.coerceIn(0, surface.width) ?: surface.width
        val minY = activeClip?.top?.coerceIn(0, surface.height) ?: 0
        val maxY = activeClip?.bottom?.coerceIn(0, surface.height) ?: surface.height
        if (minX >= maxX || minY >= maxY) return

        for (py in minY until maxY) {
            val rowOff = py * surface.width
            for (px in minX until maxX) {
                if (activeClip != null && !activeClip.contains(px, py)) continue
                val srcArgb = layerBuf[rowOff + px]
                val sa = (srcArgb ushr 24) and 0xFF
                if (sa == 0) continue
                val aOut = if (layerAlpha == 255) sa else (sa * layerAlpha) / 255
                if (aOut > 0) {
                    surface.pixels[rowOff + px] = blendSrcOver(surface.pixels[rowOff + px], (aOut shl 24) or (srcArgb and 0x00FFFFFF))
                }
            }
        }
        surface.syncRect(minX, minY, maxX, maxY)
    }

    private fun extractBitmapPixels(bitmap: Bitmap): IntArray? {
        SurfaceRegistry.peek(bitmap)?.let { return it.pixels }
        val shadow = runCatching { Shadow.extract<ShadowBitmap>(bitmap) }.getOrNull() as? ShadowLegacyBitmap
            ?: return null
        val hasBuffered = ReflectionHelpers.getField<Any?>(shadow, "bufferedImage") != null
        if (!hasBuffered) return null
        val buf = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(buf, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return buf
    }

    private fun extractColorFilterColor(filter: ColorFilter?): Int? {
        if (filter == null) return null
        return runCatching {
            val shadow = Shadow.extract<Any>(filter)
            runCatching { ReflectionHelpers.getField<Int>(shadow, "color") }.getOrNull()
                ?: ReflectionHelpers.getField<Int>(filter, "mColor")
        }.getOrNull()
    }

    private fun effectiveAlphaByte(paint: Paint?, color: Int): Int {
        val colorAlpha = (color ushr 24) and 0xFF
        val paintAlpha = paint?.alpha?.coerceIn(0, 255) ?: 255
        if (paintAlpha == 0 && colorAlpha > 0) return colorAlpha
        return if (colorAlpha == 255) paintAlpha else ((colorAlpha * paintAlpha) / 255)
    }

    private fun resolveBlendMode(paint: Paint?): BlendMode? {
        if (paint == null) return BlendMode.SRC_OVER
        val shadow = runCatching { Shadow.extract<Any>(paint) }.getOrNull() as? Arm64PaintShadow
        return shadow?.activeBlendMode() ?: paint.blendMode
    }

    private fun effectiveColorArgb(paint: Paint?): Int? {
        val mode = resolveBlendMode(paint)
        if (mode == BlendMode.CLEAR) return 0
        val color = paint?.color ?: Color.BLACK
        val alpha = effectiveAlphaByte(paint, color)
        if (alpha <= 0 && mode != BlendMode.SRC) return null
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun normalizeAngleRad(deg: Float): Float {
        var r = ((deg % 360f) + 360f) % 360f
        if (r >= 180f) r -= 360f
        return (r * PI / 180.0).toFloat()
    }
}

private data class PathContour(val points: List<FloatArray>, val closed: Boolean)

private data class AffineMatrix2D(
    var a: Float,
    var b: Float,
    var c: Float,
    var d: Float,
    var tx: Float,
    var ty: Float
) {
    val isRectPreserving: Boolean get() = abs(b) < 1e-4f && abs(c) < 1e-4f

    fun copy() = AffineMatrix2D(a, b, c, d, tx, ty)

    fun mapX(x: Float, y: Float): Float = a * x + c * y + tx

    fun mapY(x: Float, y: Float): Float = b * x + d * y + ty

    fun preTranslate(dx: Float, dy: Float) {
        tx += a * dx + c * dy
        ty += b * dx + d * dy
    }

    fun preScale(sx: Float, sy: Float) {
        a *= sx
        b *= sx
        c *= sy
        d *= sy
    }

    fun preRotate(degrees: Float) {
        val rad = degrees * PI / 180.0
        val cs = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        preConcat(AffineMatrix2D(cs, sn, -sn, cs, 0f, 0f))
    }

    fun preConcat(other: AffineMatrix2D) {
        val na = a * other.a + c * other.b
        val nb = b * other.a + d * other.b
        val nc = a * other.c + c * other.d
        val nd = b * other.c + d * other.d
        val ntx = a * other.tx + c * other.ty + tx
        val nty = b * other.tx + d * other.ty + ty
        a = na; b = nb; c = nc; d = nd; tx = ntx; ty = nty
    }

    fun invert(): AffineMatrix2D? {
        val det = a * d - b * c
        if (abs(det) < 1e-8f) return null
        val invDet = 1f / det
        val ia = d * invDet
        val ib = -b * invDet
        val ic = -c * invDet
        val id = a * invDet
        val itx = -(ia * tx + ic * ty)
        val ity = -(ib * tx + id * ty)
        return AffineMatrix2D(ia, ib, ic, id, itx, ity)
    }

    fun writeToAndroidMatrix(out: Matrix) {
        out.setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f))
    }

    companion object {
        fun identity() = AffineMatrix2D(1f, 0f, 0f, 1f, 0f, 0f)

        fun fromAndroidMatrix(m: Matrix): AffineMatrix2D {
            val v = FloatArray(9)
            m.getValues(v)
            return AffineMatrix2D(
                a = v[Matrix.MSCALE_X],
                b = v[Matrix.MSKEW_Y],
                c = v[Matrix.MSKEW_X],
                d = v[Matrix.MSCALE_Y],
                tx = v[Matrix.MTRANS_X],
                ty = v[Matrix.MTRANS_Y]
            )
        }
    }
}

private class PixelClip(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    private val mask: BooleanArray?,
    private val maskWidth: Int
) {
    val isEmpty: Boolean get() = left >= right || top >= bottom
    val hasMask: Boolean get() = mask != null

    fun copy(): PixelClip = PixelClip(left, top, right, bottom, mask?.copyOf(), maskWidth)

    fun contains(x: Int, y: Int): Boolean {
        if (x < left || x >= right || y < top || y >= bottom) return false
        return mask?.get(y * maskWidth + x) ?: true
    }

    fun applyDeviceRect(
        rl: Float,
        rt: Float,
        rr: Float,
        rb: Float,
        op: Region.Op,
        surfW: Int,
        surfH: Int
    ): PixelClip {
        val il = floor(rl).toInt().coerceIn(0, surfW)
        val it = floor(rt).toInt().coerceIn(0, surfH)
        val ir = ceil(rr).toInt().coerceIn(0, surfW)
        val ib = ceil(rb).toInt().coerceIn(0, surfH)

        if (mask == null && op == Region.Op.INTERSECT) {
            return PixelClip(max(left, il), max(top, it), min(right, ir), min(bottom, ib), null, surfW)
        }
        if (op == Region.Op.REPLACE) {
            return PixelClip(il, it, ir, ib, null, surfW)
        }
        val poly = listOf(
            floatArrayOf(rl, rt),
            floatArrayOf(rr, rt),
            floatArrayOf(rr, rb),
            floatArrayOf(rl, rb)
        )
        return applyPolygonMask(listOf(PathContour(poly, closed = true)), evenOdd = false, op, surfW, surfH)
    }

    fun applyPolygonMask(
        contours: List<PathContour>,
        evenOdd: Boolean,
        op: Region.Op,
        surfW: Int,
        surfH: Int
    ): PixelClip {
        val targetMask = mask?.copyOf() ?: BooleanArray(surfW * surfH).also { arr ->
            for (y in top until bottom) {
                val off = y * surfW
                for (x in left until right) arr[off + x] = true
            }
        }
        val polyMask = BooleanArray(surfW * surfH)
        rasterizeContoursToMask(contours, evenOdd, polyMask, surfW, surfH)

        var newL = surfW
        var newT = surfH
        var newR = 0
        var newB = 0
        for (y in 0 until surfH) {
            val off = y * surfW
            for (x in 0 until surfW) {
                val a = targetMask[off + x]
                val b = polyMask[off + x]
                val res = when (op) {
                    Region.Op.DIFFERENCE -> a && !b
                    Region.Op.UNION -> a || b
                    Region.Op.XOR -> a != b
                    Region.Op.REVERSE_DIFFERENCE -> b && !a
                    Region.Op.REPLACE -> b
                    else -> a && b
                }
                targetMask[off + x] = res
                if (res) {
                    if (x < newL) newL = x
                    if (x + 1 > newR) newR = x + 1
                    if (y < newT) newT = y
                    if (y + 1 > newB) newB = y + 1
                }
            }
        }
        return PixelClip(newL, newT, newR, newB, targetMask, surfW)
    }

    companion object {
        fun full(w: Int, h: Int) = PixelClip(0, 0, w, h, null, w)
    }
}

private class BackingSurface(val width: Int, val height: Int) {
    val pixels: IntArray = IntArray(width * height)
}

private object SurfaceRegistry {
    private val surfaces = WeakHashMap<Bitmap, BackingSurface>()

    fun peek(bitmap: Bitmap): BackingSurface? = surfaces[bitmap]

    fun forBitmap(bitmap: Bitmap): BackingSurface {
        val existing = surfaces[bitmap]
        if (existing != null && existing.width == bitmap.width && existing.height == bitmap.height) {
            return existing
        }
        return BackingSurface(bitmap.width, bitmap.height).also { created ->
            val shadow = runCatching { Shadow.extract<ShadowBitmap>(bitmap) }.getOrNull() as? ShadowLegacyBitmap
            if (shadow != null && ReflectionHelpers.getField<Any?>(shadow, "bufferedImage") != null) {
                bitmap.getPixels(created.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            surfaces[bitmap] = created
        }
    }
}

private class RasterSurface(
    val bitmap: Bitmap,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    private val syncToBitmap: Boolean
) {
    fun syncRect(minX: Int, minY: Int, maxX: Int, maxY: Int) {
        if (!syncToBitmap || !bitmap.isMutable) return
        val x = minX.coerceIn(0, width)
        val y = minY.coerceIn(0, height)
        val w = (maxX - x).coerceIn(0, width - x)
        val h = (maxY - y).coerceIn(0, height - y)
        if (w <= 0 || h <= 0) return
        bitmap.setPixels(pixels, y * width + x, width, x, y, w, h)
    }
}

private fun blendWithMode(dst: Int, src: Int, coverage: Float, mode: BlendMode?): Int {
    return when (mode) {
        BlendMode.CLEAR -> {
            val keep = (1f - coverage).coerceIn(0f, 1f)
            if (keep <= 0.01f) return 0
            val da = ((dst ushr 24) and 0xFF) * keep
            val dr = ((dst ushr 16) and 0xFF) * keep
            val dg = ((dst ushr 8) and 0xFF) * keep
            val db = (dst and 0xFF) * keep
            (da.roundToInt() shl 24) or (dr.roundToInt() shl 16) or (dg.roundToInt() shl 8) or db.roundToInt()
        }
        BlendMode.SRC -> src
        BlendMode.SRC_IN -> {
            val da = (dst ushr 24) and 0xFF
            if (da == 0) return 0
            val sa = ((src ushr 24) and 0xFF) * da / 255
            (sa shl 24) or (src and 0x00FFFFFF)
        }
        else -> blendSrcOver(dst, src)
    }
}

private fun blendSrcOver(dst: Int, src: Int): Int {
    val sa = (src ushr 24) and 0xFF
    if (sa == 0) return dst
    if (sa == 255) return src
    val da = (dst ushr 24) and 0xFF
    if (da == 0) return src

    val sr = (src ushr 16) and 0xFF
    val sg = (src ushr 8) and 0xFF
    val sb = src and 0xFF
    val dr = (dst ushr 16) and 0xFF
    val dg = (dst ushr 8) and 0xFF
    val db = dst and 0xFF

    val invSa = 255 - sa
    val outA = sa + (da * invSa) / 255
    if (outA == 0) return 0
    val outR = ((sr * sa + dr * da * invSa / 255) / outA).coerceIn(0, 255)
    val outG = ((sg * sa + dg * da * invSa / 255) / outA).coerceIn(0, 255)
    val outB = ((sb * sa + db * da * invSa / 255) / outA).coerceIn(0, 255)
    return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
}

private object PathFlattener {
    private val pathIteratorClass = runCatching { Class.forName("java.awt.geom.PathIterator") }.getOrNull()
    private val isDoneMethod = pathIteratorClass?.getMethod("isDone")
    private val nextMethod = pathIteratorClass?.getMethod("next")
    private val currentSegmentMethod = pathIteratorClass?.getMethod("currentSegment", FloatArray::class.java)
    private val shapeClass = runCatching { Class.forName("java.awt.Shape") }.getOrNull()
    private val affineTransformClass = runCatching { Class.forName("java.awt.geom.AffineTransform") }.getOrNull()
    private val getPathIteratorMethod = if (shapeClass != null && affineTransformClass != null) {
        runCatching { shapeClass?.getMethod("getPathIterator", affineTransformClass, Double::class.javaPrimitiveType) }.getOrNull()
    } else null

    fun extractContours(path: Path, matrix: AffineMatrix2D): List<PathContour> {
        val mPath = ReflectionHelpers.getField<Any?>(
            runCatching { Shadow.extract<ShadowLegacyPath>(path) }.getOrNull() ?: return emptyList(),
            "mPath"
        )
        val iter = if (mPath != null && getPathIteratorMethod != null) {
            runCatching { getPathIteratorMethod?.invoke(mPath, null, 0.5) }.getOrNull()
        } else null

        if (iter != null && isDoneMethod != null && nextMethod != null && currentSegmentMethod != null) {
            val contours = ArrayList<PathContour>()
            var currentPts = ArrayList<FloatArray>()
            val coords = FloatArray(6)
            while (!(isDoneMethod.invoke(iter) as Boolean)) {
                when (currentSegmentMethod.invoke(iter, coords) as Int) {
                    0 -> { // SEG_MOVETO
                        if (currentPts.isNotEmpty()) {
                            contours.add(PathContour(currentPts, closed = false))
                            currentPts = ArrayList()
                        }
                        currentPts.add(floatArrayOf(matrix.mapX(coords[0], coords[1]), matrix.mapY(coords[0], coords[1])))
                    }
                    1 -> { // SEG_LINETO
                        currentPts.add(floatArrayOf(matrix.mapX(coords[0], coords[1]), matrix.mapY(coords[0], coords[1])))
                    }
                    4 -> { // SEG_CLOSE
                        if (currentPts.isNotEmpty()) {
                            contours.add(PathContour(currentPts, closed = true))
                            currentPts = ArrayList()
                        }
                    }
                }
                nextMethod.invoke(iter)
            }
            if (currentPts.isNotEmpty()) {
                contours.add(PathContour(currentPts, closed = false))
            }
            return contours
        }

        // Fallback via public Android Path.approximate(0.5f)
        val approx = runCatching { path.approximate(0.5f) }.getOrNull() ?: return emptyList()
        val pts = ArrayList<FloatArray>(approx.size / 3)
        var i = 0
        while (i + 2 < approx.size) {
            pts.add(floatArrayOf(matrix.mapX(approx[i + 1], approx[i + 2]), matrix.mapY(approx[i + 1], approx[i + 2])))
            i += 3
        }
        return if (pts.size >= 2) listOf(PathContour(pts, closed = true)) else emptyList()
    }
}

private fun extractPathContours(path: Path, matrix: AffineMatrix2D): List<PathContour> =
    PathFlattener.extractContours(path, matrix)

private fun collectEdges(contours: List<PathContour>): List<FloatArray> {
    val edges = ArrayList<FloatArray>()
    for (contour in contours) {
        val pts = contour.points
        if (pts.size < 2) continue
        for (i in 0 until pts.size - 1) {
            edges.add(floatArrayOf(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]))
        }
        val last = pts.last()
        val first = pts.first()
        if (last[0] != first[0] || last[1] != first[1]) {
            edges.add(floatArrayOf(last[0], last[1], first[0], first[1]))
        }
    }
    return edges
}

private fun scanlineNodes(y: Float, edges: List<FloatArray>, evenOdd: Boolean): List<Float> {
    if (evenOdd) {
        val xs = ArrayList<Float>()
        for (e in edges) {
            val y0 = e[1]; val y1 = e[3]
            if ((y0 <= y && y1 > y) || (y1 <= y && y0 > y)) {
                xs.add(e[0] + (y - y0) * (e[2] - e[0]) / (y1 - y0))
            }
        }
        xs.sort()
        return xs
    }
    val crossings = ArrayList<Pair<Float, Int>>()
    for (e in edges) {
        val y0 = e[1]; val y1 = e[3]
        if (y0 <= y && y1 > y) {
            crossings.add((e[0] + (y - y0) * (e[2] - e[0]) / (y1 - y0)) to 1)
        } else if (y1 <= y && y0 > y) {
            crossings.add((e[0] + (y - y0) * (e[2] - e[0]) / (y1 - y0)) to -1)
        }
    }
    crossings.sortBy { it.first }
    val spans = ArrayList<Float>()
    var winding = 0
    for ((cx, dir) in crossings) {
        val prev = winding
        winding += dir
        if ((prev == 0 && winding != 0) || (prev != 0 && winding == 0)) {
            spans.add(cx)
        }
    }
    return spans
}

private fun fillPolygonContours(
    surface: RasterSurface,
    contours: List<PathContour>,
    evenOdd: Boolean,
    argb: Int,
    activeClip: PixelClip?
) {
    val edges = collectEdges(contours)
    if (edges.isEmpty()) return
    val minY = max(floor(edges.minOf { min(it[1], it[3]) }).toInt(), activeClip?.top ?: 0).coerceIn(0, surface.height)
    val maxY = min(ceil(edges.maxOf { max(it[1], it[3]) }).toInt(), activeClip?.bottom ?: surface.height).coerceIn(0, surface.height)
    val clipL = activeClip?.left ?: 0
    val clipR = activeClip?.right ?: surface.width
    var touchedL = surface.width
    var touchedR = 0

    for (py in minY until maxY) {
        val nodes = scanlineNodes(py + 0.5f, edges, evenOdd)
        val rowOff = py * surface.width
        var i = 0
        while (i + 1 < nodes.size) {
            val x0 = max(clipL, floor(nodes[i]).toInt()).coerceIn(0, surface.width)
            val x1 = min(clipR, ceil(nodes[i + 1]).toInt()).coerceIn(0, surface.width)
            if (x0 < touchedL) touchedL = x0
            if (x1 > touchedR) touchedR = x1
            for (px in x0 until x1) {
                if (activeClip == null || activeClip.contains(px, py)) {
                    surface.pixels[rowOff + px] = blendSrcOver(surface.pixels[rowOff + px], argb)
                }
            }
            i += 2
        }
    }
    if (touchedL < touchedR && minY < maxY) {
        surface.syncRect(touchedL, minY, touchedR, maxY)
    }
}

private fun rasterizeContoursToMask(
    contours: List<PathContour>,
    evenOdd: Boolean,
    outMask: BooleanArray,
    width: Int,
    height: Int
) {
    val edges = collectEdges(contours)
    if (edges.isEmpty()) return
    val minY = floor(edges.minOf { min(it[1], it[3]) }).toInt().coerceIn(0, height)
    val maxY = ceil(edges.maxOf { max(it[1], it[3]) }).toInt().coerceIn(0, height)
    for (py in minY until maxY) {
        val nodes = scanlineNodes(py + 0.5f, edges, evenOdd)
        val rowOff = py * width
        var i = 0
        while (i + 1 < nodes.size) {
            val x0 = floor(nodes[i]).toInt().coerceIn(0, width)
            val x1 = ceil(nodes[i + 1]).toInt().coerceIn(0, width)
            for (px in x0 until x1) {
                outMask[rowOff + px] = true
            }
            i += 2
        }
    }
}

private object BitmapFontMetrics {
    const val CELL_W = 5
    const val CELL_H = 7

    fun pixelSize(textSize: Float): Float = max(1.0f, textSize / 10.5f)
    fun asciiAdvance(textSize: Float): Float = pixelSize(textSize) * (CELL_W + 1.1f)
    fun cjkAdvance(textSize: Float): Float = pixelSize(textSize) * (CELL_H + 1.0f)

    fun charAdvance(ch: Char, textSize: Float): Float =
        if (ch.code in 0x20..0x7E) asciiAdvance(textSize) else cjkAdvance(textSize)

    fun measure(text: CharSequence, start: Int, end: Int, textSize: Float, textScaleX: Float): Float {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        var total = 0f
        for (i in s until e) {
            total += charAdvance(text[i], textSize)
        }
        return total * max(0f, textScaleX)
    }

    fun measure(text: CharArray, start: Int, end: Int, textSize: Float, textScaleX: Float): Float {
        val s = start.coerceIn(0, text.size)
        val e = end.coerceIn(s, text.size)
        var total = 0f
        for (i in s until e) {
            total += charAdvance(text[i], textSize)
        }
        return total * max(0f, textScaleX)
    }
}

/**
 * Pure Kotlin vector/bitmap glyph renderer avoiding `java.awt.Font` and native Fontconfig,
 * which throws `Fontconfig head is null` in headless Termux OpenJDK.
 */
private object BitmapGlyphRenderer {

    private const val CELL_W = BitmapFontMetrics.CELL_W
    private const val CELL_H = BitmapFontMetrics.CELL_H

    fun drawString(
        surface: RasterSurface,
        matrix: AffineMatrix2D,
        clip: PixelClip?,
        text: String,
        x: Float,
        baselineY: Float,
        textSize: Float,
        align: Paint.Align,
        argb: Int
    ) {
        val pixel = BitmapFontMetrics.pixelSize(textSize)
        val asciiAdvance = BitmapFontMetrics.asciiAdvance(textSize)
        val cjkAdvance = BitmapFontMetrics.cjkAdvance(textSize)

        val totalWidth = BitmapFontMetrics.measure(text, 0, text.length, textSize, 1f)

        var cursorX = when (align) {
            Paint.Align.CENTER -> x - totalWidth / 2f
            Paint.Align.RIGHT -> x - totalWidth
            Paint.Align.LEFT -> x
        }
        val topY = baselineY - pixel * CELL_H

        var dirtyMinX = surface.width
        var dirtyMinY = surface.height
        var dirtyMaxX = 0
        var dirtyMaxY = 0

        fun plotLocalRect(lx0: Float, ly0: Float, lx1: Float, ly1: Float) {
            val sx0 = floor(min(matrix.mapX(lx0, ly0), matrix.mapX(lx1, ly1))).toInt()
            val sy0 = floor(min(matrix.mapY(lx0, ly0), matrix.mapY(lx1, ly1))).toInt()
            val sx1 = max(sx0 + 1, ceil(max(matrix.mapX(lx0, ly0), matrix.mapX(lx1, ly1))).toInt())
            val sy1 = max(sy0 + 1, ceil(max(matrix.mapY(lx0, ly0), matrix.mapY(lx1, ly1))).toInt())
            val minX = max(sx0, clip?.left ?: 0).coerceIn(0, surface.width)
            val maxX = min(sx1, clip?.right ?: surface.width).coerceIn(0, surface.width)
            val minY = max(sy0, clip?.top ?: 0).coerceIn(0, surface.height)
            val maxY = min(sy1, clip?.bottom ?: surface.height).coerceIn(0, surface.height)
            if (minX >= maxX || minY >= maxY) return
            if (minX < dirtyMinX) dirtyMinX = minX
            if (minY < dirtyMinY) dirtyMinY = minY
            if (maxX > dirtyMaxX) dirtyMaxX = maxX
            if (maxY > dirtyMaxY) dirtyMaxY = maxY
            for (py in minY until maxY) {
                val off = py * surface.width
                for (px in minX until maxX) {
                    if (clip == null || clip.contains(px, py)) {
                        surface.pixels[off + px] = blendSrcOver(surface.pixels[off + px], argb)
                    }
                }
            }
        }

        for (ch in text) {
            if (ch == ' ') {
                cursorX += asciiAdvance
                continue
            }
            val bitmap = ASCII_GLYPHS[ch]
            if (bitmap != null) {
                for (row in 0 until CELL_H) {
                    val bits = bitmap[row]
                    for (col in 0 until CELL_W) {
                        if ((bits and (1 shl (CELL_W - 1 - col))) != 0) {
                            val gx = cursorX + col * pixel
                            val gy = topY + row * pixel
                            plotLocalRect(gx, gy, gx + pixel, gy + pixel)
                        }
                    }
                }
                cursorX += asciiAdvance
            } else {
                drawCjkGlyph(cursorX, topY, pixel, ch.code, ::plotLocalRect)
                cursorX += cjkAdvance
            }
        }

        if (dirtyMinX < dirtyMaxX && dirtyMinY < dirtyMaxY) {
            surface.syncRect(dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY)
        }
    }

    private inline fun drawCjkGlyph(
        x: Float,
        y: Float,
        pixel: Float,
        codePoint: Int,
        plot: (Float, Float, Float, Float) -> Unit
    ) {
        val w = pixel * (CELL_H - 0.8f)
        val h = pixel * CELL_H
        val stroke = max(1f, pixel * 0.9f)
        val midX = x + w * 0.5f - stroke * 0.5f
        val midY = y + h * 0.5f - stroke * 0.5f

        when (codePoint) {
            '·'.code, '•'.code -> plot(midX - stroke * 0.5f, midY - stroke * 0.5f, midX + stroke * 1.5f, midY + stroke * 1.5f)
            '，'.code, '。'.code -> plot(x, y + h - stroke * 2f, x + stroke * 1.5f, y + h)
            '！'.code -> {
                plot(midX, y, midX + stroke, y + h * 0.7f)
                plot(midX, y + h - stroke, midX + stroke, y + h)
            }
            else -> {
                val hash = codePoint * 2654435761L
                plot(x, y + h * 0.12f, x + w, y + h * 0.12f + stroke)
                plot(midX, y, midX + stroke, y + h)
                if ((hash and 1L) != 0L) plot(x, midY, x + w, midY + stroke)
                if ((hash and 2L) != 0L) plot(x, y + h * 0.2f, x + stroke, y + h)
                if ((hash and 4L) != 0L) plot(x + w - stroke, y + h * 0.2f, x + w, y + h)
                if ((hash and 8L) != 0L) plot(x, y + h - stroke, x + w, y + h)
            }
        }
    }

    private fun g(vararg rows: String): IntArray {
        return IntArray(CELL_H) { r ->
            var mask = 0
            val line = rows[r]
            for (c in 0 until CELL_W) {
                if (line[c] == '#') {
                    mask = mask or (1 shl (CELL_W - 1 - c))
                }
            }
            mask
        }
    }

    private val ASCII_GLYPHS: Map<Char, IntArray> = mapOf(
        '!' to g("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."),
        '"' to g(".#.#.", ".#.#.", ".....", ".....", ".....", ".....", "....."),
        '#' to g(".#.#.", "#####", ".#.#.", ".#.#.", "#####", ".#.#.", "....."),
        '$' to g("..#..", ".####", "#.#..", ".###.", "..#.#", "####.", "..#.."),
        '%' to g("##..#", "##.#.", "..#..", ".#...", "#..##", ".....", "....."),
        '&' to g(".##..", "#..#.", ".##..", "#.#.#", "#..#.", ".##.#", "....."),
        '\'' to g("..#..", "..#..", ".#...", ".....", ".....", ".....", "....."),
        '(' to g("...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."),
        ')' to g(".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."),
        '*' to g(".....", "#.#.#", ".###.", "#####", ".###.", "#.#.#", "....."),
        '+' to g(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        ',' to g(".....", ".....", ".....", ".....", "..#..", "..#..", ".#..."),
        '-' to g(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '.' to g(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        '/' to g("....#", "...#.", "..#..", "..#..", ".#...", "#....", "....."),
        '0' to g(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to g("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to g(".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"),
        '3' to g("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to g("#...#", "#...#", "#...#", "#####", "....#", "....#", "....#"),
        '5' to g("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to g(".###.", "#....", "####.", "#...#", "#...#", "#...#", ".###."),
        '7' to g("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to g(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to g(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
        ':' to g(".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."),
        ';' to g(".....", ".##..", ".##..", ".....", ".##..", "..#..", ".#..."),
        '<' to g("...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#."),
        '=' to g(".....", ".....", "#####", ".....", "#####", ".....", "....."),
        '>' to g(".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#..."),
        '?' to g(".###.", "#...#", "....#", "..##.", "..#..", ".....", "..#.."),
        '@' to g(".###.", "#...#", "#.###", "#.###", "#.##.", "#....", ".###."),
        'A' to g("..#..", ".#.#.", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to g("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to g(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to g("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to g("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to g("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to g(".###.", "#...#", "#....", "#..##", "#...#", "#...#", ".####"),
        'H' to g("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to g(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to g("..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."),
        'K' to g("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to g("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to g("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to g("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'O' to g(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to g("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to g(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to g("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to g(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to g("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to g("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to g("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to g("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
        'X' to g("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to g("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to g("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '[' to g(".###.", ".#...", ".#...", ".#...", ".#...", ".#...", ".###."),
        '\\' to g("#....", ".#...", "..#..", "..#..", "...#.", "....#", "....."),
        ']' to g(".###.", "...#.", "...#.", "...#.", "...#.", "...#.", ".###."),
        '^' to g("..#..", ".#.#.", "#...#", ".....", ".....", ".....", "....."),
        '_' to g(".....", ".....", ".....", ".....", ".....", ".....", "#####"),
        '`' to g(".#...", "..#..", "...#.", ".....", ".....", ".....", "....."),
        'a' to g(".....", ".....", ".###.", "....#", ".####", "#...#", ".####"),
        'b' to g("#....", "#....", "####.", "#...#", "#...#", "#...#", "####."),
        'c' to g(".....", ".....", ".###.", "#....", "#....", "#...#", ".###."),
        'd' to g("....#", "....#", ".####", "#...#", "#...#", "#...#", ".####"),
        'e' to g(".....", ".....", ".###.", "#...#", "#####", "#....", ".###."),
        'f' to g("..##.", ".#...", "####.", ".#...", ".#...", ".#...", ".#..."),
        'g' to g(".....", ".####", "#...#", "#...#", ".####", "....#", ".###."),
        'h' to g("#....", "#....", "####.", "#...#", "#...#", "#...#", "#...#"),
        'i' to g("..#..", ".....", ".##..", "..#..", "..#..", "..#..", ".###."),
        'j' to g("...#.", ".....", "..##.", "...#.", "...#.", "#..#.", ".##.."),
        'k' to g("#....", "#....", "#..#.", "#.#..", "##...", "#.#..", "#..#."),
        'l' to g(".##..", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'm' to g(".....", ".....", "##.#.", "#.#.#", "#.#.#", "#...#", "#...#"),
        'n' to g(".....", ".....", "####.", "#...#", "#...#", "#...#", "#...#"),
        'o' to g(".....", ".....", ".###.", "#...#", "#...#", "#...#", ".###."),
        'p' to g(".....", "####.", "#...#", "#...#", "####.", "#....", "#...."),
        'q' to g(".....", ".####", "#...#", "#...#", ".####", "....#", "....#"),
        'r' to g(".....", ".....", "#.##.", "##...", "#....", "#....", "#...."),
        's' to g(".....", ".....", ".####", "#....", ".###.", "....#", "####."),
        't' to g(".#...", ".#...", "####.", ".#...", ".#...", ".#...", "..##."),
        'u' to g(".....", ".....", "#...#", "#...#", "#...#", "#..##", ".##.#"),
        'v' to g(".....", ".....", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'w' to g(".....", ".....", "#...#", "#...#", "#.#.#", "#.#.#", ".#.#."),
        'x' to g(".....", ".....", "#...#", ".#.#.", "..#..", ".#.#.", "#...#"),
        'y' to g(".....", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        'z' to g(".....", ".....", "#####", "...#.", "..#..", ".#...", "#####"),
        '{' to g("...##", "..#..", "..#..", ".#...", "..#..", "..#..", "...##"),
        '|' to g("..#..", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        '}' to g("##...", "..#..", "..#..", "...#.", "..#..", "..#..", "##..."),
        '~' to g(".....", ".#..#", "#.##.", ".....", ".....", ".....", ".....")
    )
}

/**
 * Forces Compose's [AndroidComposeView] host into `ViewLayer` software layer mode instead of
 * hardware `RenderNode`s without forcing View-level `LAYER_TYPE_SOFTWARE` invalidations.
 */
internal fun configureSoftwareHostView(hostView: View): View {
    runCatching { ReflectionHelpers.setField(hostView, "isRenderNodeCompatible", false) }
    return hostView
}

/**
 * Captures [hostView] into an ARGB [Bitmap] using software rendering, verifies that multiple distinct
 * pixel values were rendered (non-blank), and writes a lossless PNG screenshot under `build/reports/screenshots`.
 */
internal fun captureSoftwareScreenshot(hostView: View, fileName: String): File {
    assertTrue("Host view width must be positive", hostView.width > 0)
    assertTrue("Host view height must be positive", hostView.height > 0)

    configureSoftwareHostView(hostView)
    val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
    hostView.draw(android.graphics.Canvas(bitmap))

    val firstPixel = bitmap.getPixel(0, 0)
    var hasDistinctPixel = false
    for (y in 0 until bitmap.height step 16) {
        for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != firstPixel) {
                hasDistinctPixel = true
                break
            }
        }
        if (hasDistinctPixel) break
    }
    assertTrue("Captured screenshot must not be blank ($fileName)", hasDistinctPixel)

    val outDir = File("build/reports/screenshots")
    if (!outDir.exists() && !outDir.mkdirs()) {
        throw IOException("Failed to create screenshot directory: ${outDir.absolutePath}")
    }
    val outFile = File(outDir, fileName)
    FileOutputStream(outFile).use { stream ->
        val saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        assertTrue("Failed to save PNG screenshot: ${outFile.absolutePath}", saved)
    }
    return outFile
}
