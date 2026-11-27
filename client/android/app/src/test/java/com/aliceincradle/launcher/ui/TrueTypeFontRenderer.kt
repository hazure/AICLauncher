package com.aliceincradle.launcher.ui

import android.graphics.BlendMode
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin TrueType (`glyf`) font parser and 4x sub-scanline anti-aliased glyph rasterizer
 * for headless Termux OpenJDK Robolectric screenshot tests where `java.awt.Font` / Fontconfig
 * is unavailable.
 */
internal object TrueTypeFontRenderer {

    private const val FONT_RESOURCE_PATH = "fonts/HarmonyOS_Sans_SC_Regular.ttf"

    private val loadedFont: TrueTypeFont? by lazy {
        try {
            val stream = TrueTypeFontRenderer::class.java.classLoader
                ?.getResourceAsStream(FONT_RESOURCE_PATH)
            if (stream == null) {
                System.err.println("[TrueTypeFontRenderer] Resource not found: $FONT_RESOURCE_PATH")
                null
            } else {
                val bytes = stream.use { it.readBytes() }
                TrueTypeFont.parse(bytes)
            }
        } catch (t: Throwable) {
            System.err.println("[TrueTypeFontRenderer] Failed to load $FONT_RESOURCE_PATH: ${t.message}")
            null
        }
    }

    val isLoaded: Boolean
        get() = loadedFont != null

    internal data class ScaledFontMetrics(
        val top: Float,
        val ascent: Float,
        val descent: Float,
        val bottom: Float,
        val leading: Float
    )

    fun fontMetrics(textSize: Float): ScaledFontMetrics {
        val font = loadedFont ?: return ScaledFontMetrics(
            top = -textSize * 1.05f,
            ascent = -textSize * 0.90f,
            descent = textSize * 0.25f,
            bottom = textSize * 0.30f,
            leading = 0f
        )
        val scale = textSize / font.unitsPerEm.toFloat()
        val ascent = -font.ascent.toFloat() * scale
        val descent = -font.descent.toFloat() * scale
        val leading = font.lineGap.toFloat() * scale
        return ScaledFontMetrics(
            top = ascent * 1.12f,
            ascent = ascent,
            descent = descent,
            bottom = descent * 1.15f,
            leading = leading
        )
    }

    fun charAdvance(codePoint: Int, textSize: Float): Float {
        val font = loadedFont ?: return textSize * 0.6f
        val gid = font.glyphIdForCodePoint(codePoint)
        return font.advanceWidth(gid).toFloat() * (textSize / font.unitsPerEm.toFloat())
    }

    fun measure(
        text: CharSequence,
        start: Int,
        end: Int,
        textSize: Float,
        textScaleX: Float
    ): Float {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        var total = 0f
        var i = s
        while (i < e) {
            val cp = Character.codePointAt(text, i)
            total += charAdvance(cp, textSize)
            i += Character.charCount(cp)
        }
        return total * max(0f, textScaleX)
    }

    fun measure(
        text: CharArray,
        start: Int,
        end: Int,
        textSize: Float,
        textScaleX: Float
    ): Float {
        val s = start.coerceIn(0, text.size)
        val e = end.coerceIn(s, text.size)
        var total = 0f
        var i = s
        while (i < e) {
            val cp = Character.codePointAt(text, i, e)
            total += charAdvance(cp, textSize)
            i += Character.charCount(cp)
        }
        return total * max(0f, textScaleX)
    }

    fun drawString(
        surface: RasterSurface,
        matrix: AffineMatrix2D,
        clip: PixelClip?,
        text: String,
        x: Float,
        baselineY: Float,
        textSize: Float,
        textScaleX: Float,
        align: Paint.Align,
        argb: Int,
        isBold: Boolean,
        blendMode: BlendMode?
    ) {
        val font = loadedFont ?: return
        if (text.isEmpty()) return

        val sx = max(0f, textScaleX)
        val totalWidth = measure(text, 0, text.length, textSize, sx)
        var cursorX = when (align) {
            Paint.Align.CENTER -> x - totalWidth / 2f
            Paint.Align.RIGHT -> x - totalWidth
            Paint.Align.LEFT -> x
        }

        val ox = matrix.mapX(0f, 0f)
        val oy = matrix.mapY(0f, 0f)
        val scaleXFactor = kotlin.math.hypot(matrix.mapX(1f, 0f) - ox, matrix.mapY(1f, 0f) - oy)
        val scaleYFactor = kotlin.math.hypot(matrix.mapX(0f, 1f) - ox, matrix.mapY(0f, 1f) - oy)
        val renderSize = max(6f, textSize * ((scaleXFactor + scaleYFactor) * 0.5f))
        val quantizedSize = (renderSize * 2f).roundToInt().coerceAtLeast(12)

        var dirtyMinX = surface.width
        var dirtyMinY = surface.height
        var dirtyMaxX = 0
        var dirtyMaxY = 0

        val clipL = clip?.left ?: 0
        val clipT = clip?.top ?: 0
        val clipR = clip?.right ?: surface.width
        val clipB = clip?.bottom ?: surface.height

        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charLen = Character.charCount(cp)
            val gid = font.glyphIdForCodePoint(cp)
            val advance = font.advanceWidth(gid).toFloat() * (textSize / font.unitsPerEm.toFloat()) * sx

            val glyph = font.rasterizeGlyph(gid, quantizedSize, isBold)
            if (glyph != null && glyph.width > 0 && glyph.height > 0) {
                val originX = matrix.mapX(cursorX, baselineY).roundToInt() + glyph.offsetX
                val originY = matrix.mapY(cursorX, baselineY).roundToInt() + glyph.offsetY

                val startX = max(originX, clipL).coerceIn(0, surface.width)
                val endX = min(originX + glyph.width, clipR).coerceIn(0, surface.width)
                val startY = max(originY, clipT).coerceIn(0, surface.height)
                val endY = min(originY + glyph.height, clipB).coerceIn(0, surface.height)

                if (startX < endX && startY < endY) {
                    if (startX < dirtyMinX) dirtyMinX = startX
                    if (startY < dirtyMinY) dirtyMinY = startY
                    if (endX > dirtyMaxX) dirtyMaxX = endX
                    if (endY > dirtyMaxY) dirtyMaxY = endY

                    for (py in startY until endY) {
                        val gy = py - originY
                        val gRow = gy * glyph.width
                        val sRow = py * surface.width
                        for (px in startX until endX) {
                            val gx = px - originX
                            val covByte = glyph.alpha[gRow + gx].toInt() and 0xFF
                            if (covByte == 0) continue
                            if (clip != null && !clip.contains(px, py)) continue
                            val coverage = covByte / 255f
                            surface.pixels[sRow + px] = blendWithMode(
                                surface.pixels[sRow + px],
                                argb,
                                coverage,
                                blendMode
                            )
                        }
                    }
                }
            }

            cursorX += advance
            i += charLen
        }

        if (dirtyMinX < dirtyMaxX && dirtyMinY < dirtyMaxY) {
            surface.syncRect(dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY)
        }
    }
}

private val cachedRenderingHints: Any? by lazy {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")
    runCatching {
        val hintsClass = Class.forName("java.awt.RenderingHints")
        val hintMap = mapOf(
            hintsClass.getField("KEY_TEXT_ANTIALIASING").get(null) to hintsClass.getField("VALUE_TEXT_ANTIALIAS_ON").get(null),
            hintsClass.getField("KEY_ANTIALIASING").get(null) to hintsClass.getField("VALUE_ANTIALIAS_ON").get(null),
            hintsClass.getField("KEY_FRACTIONALMETRICS").get(null) to hintsClass.getField("VALUE_FRACTIONALMETRICS_ON").get(null),
            hintsClass.getField("KEY_RENDERING").get(null) to hintsClass.getField("VALUE_RENDER_QUALITY").get(null)
        )
        hintsClass.getConstructor(Map::class.java).newInstance(hintMap)
    }.onFailure { err ->
        System.err.println("[TrueTypeFontRenderer] RenderingHints unavailable: ${err.message}")
    }.getOrNull()
}

internal fun configureAntiAliasingRenderingHints(): Any? = cachedRenderingHints

private val cachedTestFontFamily: FontFamily by lazy {
    runCatching {
        val stream = TrueTypeFontRenderer::class.java.classLoader
            ?.getResourceAsStream("fonts/HarmonyOS_Sans_SC_Regular.ttf")
            ?: return@runCatching FontFamily.SansSerif
        val tempFile = File.createTempFile("aicl_test_harmonyos_sc_", ".ttf").apply {
            deleteOnExit()
        }
        stream.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        FontFamily(Font(tempFile))
    }.getOrElse { err ->
        System.err.println("[TestCjkTheme] FontFamily(Font) fallback to SansSerif: ${err.message}")
        FontFamily.SansSerif
    }
}

internal fun testCjkFontFamily(): FontFamily = cachedTestFontFamily

private fun Typography.withFontFamily(fontFamily: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily)
)

@Composable
internal fun TestCjkTheme(content: @Composable () -> Unit) {
    val family = testCjkFontFamily()
    MaterialTheme(typography = MaterialTheme.typography.withFontFamily(family)) {
        ProvideTextStyle(value = TextStyle(fontFamily = family)) {
            content()
        }
    }
}

private class TrueTypeFont private constructor(
    private val data: ByteBuffer,
    val unitsPerEm: Int,
    val ascent: Int,
    val descent: Int,
    val lineGap: Int,
    private val numGlyphs: Int,
    private val indexToLocFormat: Int,
    private val numberOfHMetrics: Int,
    private val hmtxOffset: Int,
    private val locaOffset: Int,
    private val glyfOffset: Int,
    private val cmapLookup: CmapTable
) {
    private data class GlyphPoint(val x: Float, val y: Float, val onCurve: Boolean)
    private data class GlyphOutline(val contours: List<List<GlyphPoint>>)
    private data class RasterKey(val glyphId: Int, val quantizedSize: Int, val isBold: Boolean)

    data class RasterizedGlyph(
        val offsetX: Int,
        val offsetY: Int,
        val width: Int,
        val height: Int,
        val alpha: ByteArray
    )

    private val outlineCache = ConcurrentHashMap<Int, GlyphOutline>()
    private val rasterCache = ConcurrentHashMap<RasterKey, RasterizedGlyph>()

    fun glyphIdForCodePoint(codePoint: Int): Int = cmapLookup.lookup(codePoint)

    fun advanceWidth(glyphId: Int): Int {
        if (glyphId < 0 || numGlyphs <= 0) return unitsPerEm / 2
        val idx = min(glyphId, numberOfHMetrics - 1).coerceAtLeast(0)
        return u16(hmtxOffset + idx * 4)
    }

    fun rasterizeGlyph(glyphId: Int, quantizedSize: Int, isBold: Boolean): RasterizedGlyph? {
        val key = RasterKey(glyphId, quantizedSize, isBold)
        rasterCache[key]?.let { return it }

        val outline = getOutline(glyphId)
        if (outline.contours.isEmpty()) {
            val empty = RasterizedGlyph(0, 0, 0, 0, ByteArray(0))
            rasterCache[key] = empty
            return empty
        }

        val textSize = quantizedSize / 2f
        val scale = textSize / unitsPerEm.toFloat()
        val edges = ArrayList<FloatArray>()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (contour in outline.contours) {
            val flattened = flattenTrueTypeContour(contour, scale)
            if (flattened.size < 2) continue
            for (i in 0 until flattened.size - 1) {
                val p0 = flattened[i]
                val p1 = flattened[i + 1]
                edges.add(floatArrayOf(p0[0], p0[1], p1[0], p1[1]))
                minX = min(minX, min(p0[0], p1[0]))
                minY = min(minY, min(p0[1], p1[1]))
                maxX = max(maxX, max(p0[0], p1[0]))
                maxY = max(maxY, max(p0[1], p1[1]))
            }
            val first = flattened.first()
            val last = flattened.last()
            if (last[0] != first[0] || last[1] != first[1]) {
                edges.add(floatArrayOf(last[0], last[1], first[0], first[1]))
            }
        }

        if (edges.isEmpty() || !minX.isFinite() || !minY.isFinite()) {
            val empty = RasterizedGlyph(0, 0, 0, 0, ByteArray(0))
            rasterCache[key] = empty
            return empty
        }

        val pad = if (isBold) 2 else 1
        val x0 = floor(minX).toInt() - pad
        val y0 = floor(minY).toInt() - pad
        val x1 = ceil(maxX).toInt() + pad
        val y1 = ceil(maxY).toInt() + pad
        val width = (x1 - x0).coerceAtLeast(1)
        val height = (y1 - y0).coerceAtLeast(1)

        val accum = FloatArray(width * height)
        val subSamples = 8
        val invSub = 1f / subSamples.toFloat()
        val boldExpand = if (isBold) max(0.25f, textSize * 0.018f) else 0f

        for (py in 0 until height) {
            val worldPy = (y0 + py).toFloat()
            val rowOff = py * width
            for (s in 0 until subSamples) {
                val scanY = worldPy + (s + 0.5f) * invSub
                val spans = scanlineNonZeroSpans(scanY, edges)
                var i = 0
                while (i + 1 < spans.size) {
                    val sx0 = spans[i] - boldExpand - x0
                    val sx1 = spans[i + 1] + boldExpand - x0
                    val ix0 = floor(sx0).toInt().coerceIn(0, width - 1)
                    val ix1 = floor(sx1).toInt().coerceIn(0, width - 1)
                    for (px in ix0..ix1) {
                        val cov = (min(px + 1f, sx1) - max(px.toFloat(), sx0)).coerceIn(0f, 1f)
                        if (cov > 0f) {
                            accum[rowOff + px] += cov * invSub
                        }
                    }
                    i += 2
                }
            }
        }

        val alpha = ByteArray(width * height)
        for (idx in accum.indices) {
            alpha[idx] = (accum[idx].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toByte()
        }

        val result = RasterizedGlyph(x0, y0, width, height, alpha)
        rasterCache[key] = result
        return result
    }

    private fun scanlineNonZeroSpans(y: Float, edges: List<FloatArray>): List<Float> {
        val crossings = ArrayList<Pair<Float, Int>>()
        for (e in edges) {
            val y0 = e[1]
            val y1 = e[3]
            if (y0 <= y && y1 > y) {
                val cx = e[0] + (y - y0) * (e[2] - e[0]) / (y1 - y0)
                crossings.add(cx to 1)
            } else if (y1 <= y && y0 > y) {
                val cx = e[0] + (y - y0) * (e[2] - e[0]) / (y1 - y0)
                crossings.add(cx to -1)
            }
        }
        if (crossings.isEmpty()) return emptyList()
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

    private fun flattenTrueTypeContour(contour: List<GlyphPoint>, scale: Float): List<FloatArray> {
        if (contour.isEmpty()) return emptyList()
        // Normalize contour so it starts on an on-curve point
        val pts = ArrayList<GlyphPoint>(contour.size + 4)
        val first = contour[0]
        val last = contour[contour.size - 1]
        if (!first.onCurve) {
            if (last.onCurve) {
                pts.add(last)
                pts.addAll(contour.subList(0, contour.size - 1))
            } else {
                pts.add(GlyphPoint((last.x + first.x) * 0.5f, (last.y + first.y) * 0.5f, true))
                pts.addAll(contour)
            }
        } else {
            pts.addAll(contour)
        }

        val out = ArrayList<FloatArray>(pts.size * 2)
        val startPt = pts[0]
        out.add(floatArrayOf(startPt.x * scale, -startPt.y * scale))

        var i = 1
        val n = pts.size
        while (i < n) {
            val curr = pts[i]
            if (curr.onCurve) {
                out.add(floatArrayOf(curr.x * scale, -curr.y * scale))
                i++
            } else {
                val next = if (i + 1 < n) pts[i + 1] else pts[0]
                val endX: Float
                val endY: Float
                if (next.onCurve) {
                    endX = next.x
                    endY = next.y
                    i += 2
                } else {
                    endX = (curr.x + next.x) * 0.5f
                    endY = (curr.y + next.y) * 0.5f
                    i += 1
                }
                val prevOut = out.last()
                val p0x = prevOut[0]
                val p0y = prevOut[1]
                val p1x = curr.x * scale
                val p1y = -curr.y * scale
                val p2x = endX * scale
                val p2y = -endY * scale

                val dist = abs(p1x - (p0x + p2x) * 0.5f) + abs(p1y - (p0y + p2y) * 0.5f)
                val steps = max(4, min(16, ceil(dist * 2.5f).toInt()))
                for (step in 1..steps) {
                    val t = step.toFloat() / steps.toFloat()
                    val omt = 1f - t
                    val qx = omt * omt * p0x + 2f * omt * t * p1x + t * t * p2x
                    val qy = omt * omt * p0y + 2f * omt * t * p1y + t * t * p2y
                    out.add(floatArrayOf(qx, qy))
                }
            }
        }
        return out
    }

    private fun getOutline(glyphId: Int, depth: Int = 0): GlyphOutline {
        if (depth > 8 || glyphId < 0 || glyphId >= numGlyphs) return GlyphOutline(emptyList())
        outlineCache[glyphId]?.let { return it }

        val startOff = glyphOffset(glyphId)
        val endOff = glyphOffset(glyphId + 1)
        if (startOff < 0 || endOff <= startOff) {
            val empty = GlyphOutline(emptyList())
            outlineCache[glyphId] = empty
            return empty
        }

        val pos = glyfOffset + startOff
        val numberOfContours = s16(pos)
        val result = if (numberOfContours >= 0) {
            parseSimpleGlyph(pos + 10, numberOfContours)
        } else {
            parseCompoundGlyph(pos + 10, depth)
        }
        outlineCache[glyphId] = result
        return result
    }

    private fun parseSimpleGlyph(startPos: Int, numberOfContours: Int): GlyphOutline {
        if (numberOfContours <= 0) return GlyphOutline(emptyList())
        var p = startPos
        val endPts = IntArray(numberOfContours)
        for (i in 0 until numberOfContours) {
            endPts[i] = u16(p)
            p += 2
        }
        val numPoints = endPts[numberOfContours - 1] + 1
        if (numPoints <= 0) return GlyphOutline(emptyList())

        val instructionLen = u16(p)
        p += 2 + instructionLen

        val flags = IntArray(numPoints)
        var fi = 0
        while (fi < numPoints) {
            val flag = u8(p++)
            flags[fi++] = flag
            if ((flag and 0x08) != 0 && fi < numPoints) {
                val repeat = u8(p++)
                for (r in 0 until repeat) {
                    if (fi >= numPoints) break
                    flags[fi++] = flag
                }
            }
        }

        val xs = IntArray(numPoints)
        var curX = 0
        for (i in 0 until numPoints) {
            val flag = flags[i]
            val xShort = (flag and 0x02) != 0
            val xSameOrPos = (flag and 0x10) != 0
            curX += when {
                xShort && xSameOrPos -> u8(p++)
                xShort && !xSameOrPos -> -u8(p++)
                !xShort && xSameOrPos -> 0
                else -> {
                    val v = s16(p)
                    p += 2
                    v
                }
            }
            xs[i] = curX
        }

        val ys = IntArray(numPoints)
        var curY = 0
        for (i in 0 until numPoints) {
            val flag = flags[i]
            val yShort = (flag and 0x04) != 0
            val ySameOrPos = (flag and 0x20) != 0
            curY += when {
                yShort && ySameOrPos -> u8(p++)
                yShort && !ySameOrPos -> -u8(p++)
                !yShort && ySameOrPos -> 0
                else -> {
                    val v = s16(p)
                    p += 2
                    v
                }
            }
            ys[i] = curY
        }

        val contours = ArrayList<List<GlyphPoint>>(numberOfContours)
        var startIdx = 0
        for (c in 0 until numberOfContours) {
            val endIdx = endPts[c]
            val pts = ArrayList<GlyphPoint>(endIdx - startIdx + 1)
            for (i in startIdx..endIdx) {
                pts.add(GlyphPoint(xs[i].toFloat(), ys[i].toFloat(), (flags[i] and 0x01) != 0))
            }
            contours.add(pts)
            startIdx = endIdx + 1
        }
        return GlyphOutline(contours)
    }

    private fun parseCompoundGlyph(startPos: Int, depth: Int): GlyphOutline {
        var p = startPos
        val allContours = ArrayList<List<GlyphPoint>>()
        while (true) {
            val flags = u16(p)
            val componentGid = u16(p + 2)
            p += 4

            val arg1: Int
            val arg2: Int
            if ((flags and 0x0001) != 0) {
                arg1 = s16(p)
                arg2 = s16(p + 2)
                p += 4
            } else {
                arg1 = s8(p)
                arg2 = s8(p + 1)
                p += 2
            }

            var a = 1f
            var b = 0f
            var c = 0f
            var d = 1f
            when {
                (flags and 0x0008) != 0 -> {
                    val s = f2Dot14(p)
                    p += 2
                    a = s
                    d = s
                }
                (flags and 0x0040) != 0 -> {
                    a = f2Dot14(p)
                    d = f2Dot14(p + 2)
                    p += 4
                }
                (flags and 0x0080) != 0 -> {
                    a = f2Dot14(p)
                    b = f2Dot14(p + 2)
                    c = f2Dot14(p + 4)
                    d = f2Dot14(p + 6)
                    p += 8
                }
            }

            val dx = if ((flags and 0x0002) != 0) arg1.toFloat() else 0f
            val dy = if ((flags and 0x0002) != 0) arg2.toFloat() else 0f

            val compOutline = getOutline(componentGid, depth + 1)
            for (contour in compOutline.contours) {
                allContours.add(
                    contour.map { pt ->
                        GlyphPoint(
                            x = a * pt.x + c * pt.y + dx,
                            y = b * pt.x + d * pt.y + dy,
                            onCurve = pt.onCurve
                        )
                    }
                )
            }

            if ((flags and 0x0020) == 0) break
        }
        return GlyphOutline(allContours)
    }

    private fun glyphOffset(glyphId: Int): Int {
        return if (indexToLocFormat == 0) {
            u16(locaOffset + glyphId * 2) * 2
        } else {
            u32(locaOffset + glyphId * 4).toInt()
        }
    }

    private fun u8(offset: Int): Int = data.get(offset).toInt() and 0xFF
    private fun s8(offset: Int): Int = data.get(offset).toInt()
    private fun u16(offset: Int): Int = data.getShort(offset).toInt() and 0xFFFF
    private fun s16(offset: Int): Int = data.getShort(offset).toInt()
    private fun u32(offset: Int): Long = data.getInt(offset).toLong() and 0xFFFFFFFFL
    private fun f2Dot14(offset: Int): Float = s16(offset).toFloat() / 16384f

    private sealed interface CmapTable {
        fun lookup(codePoint: Int): Int
    }

    private class CmapFormat12(
        private val startChars: IntArray,
        private val endChars: IntArray,
        private val startGlyphs: IntArray
    ) : CmapTable {
        override fun lookup(codePoint: Int): Int {
            var lo = 0
            var hi = startChars.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val s = startChars[mid]
                val e = endChars[mid]
                when {
                    codePoint < s -> hi = mid - 1
                    codePoint > e -> lo = mid + 1
                    else -> return startGlyphs[mid] + (codePoint - s)
                }
            }
            return 0
        }
    }

    private class CmapFormat4(
        private val data: ByteBuffer,
        private val segCount: Int,
        private val endCodes: IntArray,
        private val startCodes: IntArray,
        private val idDeltas: IntArray,
        private val idRangeOffsets: IntArray,
        private val idRangeOffsetBase: Int
    ) : CmapTable {
        override fun lookup(codePoint: Int): Int {
            if (codePoint !in 0..0xFFFF) return 0
            var lo = 0
            var hi = segCount - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val s = startCodes[mid]
                val e = endCodes[mid]
                when {
                    codePoint < s -> hi = mid - 1
                    codePoint > e -> lo = mid + 1
                    else -> {
                        val rangeOff = idRangeOffsets[mid]
                        if (rangeOff == 0) {
                            return (codePoint + idDeltas[mid]) and 0xFFFF
                        }
                        val addr = idRangeOffsetBase + mid * 2 + rangeOff + (codePoint - s) * 2
                        val gid = data.getShort(addr).toInt() and 0xFFFF
                        return if (gid == 0) 0 else (gid + idDeltas[mid]) and 0xFFFF
                    }
                }
            }
            return 0
        }
    }

    companion object {
        fun parse(bytes: ByteArray): TrueTypeFont? {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val numTables = buf.getShort(4).toInt() and 0xFFFF
            val tables = HashMap<String, Int>(numTables)
            var dirPos = 12
            for (i in 0 until numTables) {
                val tag = String(bytes, dirPos, 4, Charsets.US_ASCII)
                val offset = buf.getInt(dirPos + 8)
                tables[tag] = offset
                dirPos += 16
            }

            val headOff = tables["head"] ?: return null
            val maxpOff = tables["maxp"] ?: return null
            val hheaOff = tables["hhea"] ?: return null
            val hmtxOff = tables["hmtx"] ?: return null
            val cmapOff = tables["cmap"] ?: return null
            val locaOff = tables["loca"] ?: return null
            val glyfOff = tables["glyf"] ?: return null

            val unitsPerEm = buf.getShort(headOff + 18).toInt() and 0xFFFF
            val indexToLocFormat = buf.getShort(headOff + 50).toInt()
            val numGlyphs = buf.getShort(maxpOff + 4).toInt() and 0xFFFF
            val ascent = buf.getShort(hheaOff + 4).toInt()
            val descent = buf.getShort(hheaOff + 6).toInt()
            val lineGap = buf.getShort(hheaOff + 8).toInt()
            val numberOfHMetrics = buf.getShort(hheaOff + 34).toInt() and 0xFFFF

            val cmap = parseCmap(buf, cmapOff) ?: return null

            return TrueTypeFont(
                data = buf,
                unitsPerEm = unitsPerEm,
                ascent = ascent,
                descent = descent,
                lineGap = lineGap,
                numGlyphs = numGlyphs,
                indexToLocFormat = indexToLocFormat,
                numberOfHMetrics = numberOfHMetrics,
                hmtxOffset = hmtxOff,
                locaOffset = locaOff,
                glyfOffset = glyfOff,
                cmapLookup = cmap
            )
        }

        private fun parseCmap(buf: ByteBuffer, cmapOff: Int): CmapTable? {
            val numSubtables = buf.getShort(cmapOff + 2).toInt() and 0xFFFF
            var format12Offset = -1
            var format4Offset = -1
            for (i in 0 until numSubtables) {
                val rec = cmapOff + 4 + i * 8
                val platformId = buf.getShort(rec).toInt() and 0xFFFF
                val encodingId = buf.getShort(rec + 2).toInt() and 0xFFFF
                val subOffset = cmapOff + buf.getInt(rec + 4)
                val format = buf.getShort(subOffset).toInt() and 0xFFFF
                if (format == 12 && (platformId == 3 && encodingId == 10 || platformId == 0)) {
                    format12Offset = subOffset
                } else if (format == 4 && format4Offset < 0 && (platformId == 3 && encodingId == 1 || platformId == 0)) {
                    format4Offset = subOffset
                }
            }
            if (format12Offset >= 0) {
                val numGroups = buf.getInt(format12Offset + 12)
                val startChars = IntArray(numGroups)
                val endChars = IntArray(numGroups)
                val startGlyphs = IntArray(numGroups)
                var gp = format12Offset + 16
                for (i in 0 until numGroups) {
                    startChars[i] = buf.getInt(gp)
                    endChars[i] = buf.getInt(gp + 4)
                    startGlyphs[i] = buf.getInt(gp + 8)
                    gp += 12
                }
                return CmapFormat12(startChars, endChars, startGlyphs)
            }
            if (format4Offset >= 0) {
                val segCount = (buf.getShort(format4Offset + 6).toInt() and 0xFFFF) / 2
                val endBase = format4Offset + 14
                val startBase = endBase + segCount * 2 + 2
                val deltaBase = startBase + segCount * 2
                val rangeBase = deltaBase + segCount * 2
                val endCodes = IntArray(segCount) { buf.getShort(endBase + it * 2).toInt() and 0xFFFF }
                val startCodes = IntArray(segCount) { buf.getShort(startBase + it * 2).toInt() and 0xFFFF }
                val idDeltas = IntArray(segCount) { buf.getShort(deltaBase + it * 2).toInt() }
                val idRangeOffsets = IntArray(segCount) { buf.getShort(rangeBase + it * 2).toInt() and 0xFFFF }
                return CmapFormat4(buf, segCount, endCodes, startCodes, idDeltas, idRangeOffsets, rangeBase)
            }
            return null
        }
    }
}
