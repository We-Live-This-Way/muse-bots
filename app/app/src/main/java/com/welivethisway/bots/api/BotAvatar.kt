package com.welivethisway.bots.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Base64
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bot avatars, matching the official apps.
 *
 * Rakazo has no avatar image URL: a bot's avatar is derived client-side
 * from `color` + `avatarSet` (packages/contracts bot-avatar.ts,
 * packages/ui-web bot-avatar.tsx). MausBot carries a real `avatarUrl`
 * (GET /api/bots) with crop/zoom/focus, falling back to the gradient
 * mouse mascot (src/components Avatar.tsx).
 *
 * Deviations from the official renderers, all deliberate and static:
 * - Rakazo eyes are a contrasting ink instead of cut-outs in the page
 *   colour, so they read on any background (list row or dark header).
 * - The Rakazo logo mark is the flat body path with eyes — the official
 *   version adds hands and directional lighting, which this app skips.
 * - The MausBot mascot is static (no blink / expression morphs) and has
 *   no mouth; the body, gradient and idle eye rings are the real ones.
 */

// ---- Shared colour helpers ----

private val HEX_RE = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

fun expandHex(hex: String): String {
    val c = hex.removePrefix("#")
    if (c.length == 3) return "#" + c.map { "$it$it" }.joinToString("")
    return "#$c"
}

fun parseHexColor(hex: String): Int? {
    return try {
        if (!HEX_RE.matches(hex)) null else Color.parseColor(expandHex(hex))
    } catch (e: Exception) {
        null
    }
}

private data class BotColorDef(val id: String, val name: String, val hex: String, val previousHex: String?)

/** The one Rakazo bot palette (BOT_COLOR_LIST, bot-avatar.ts). */
private val BOT_COLOR_LIST = listOf(
    BotColorDef("white", "Staff White", "#FFFFFF", null),
    BotColorDef("violet", "Executive Violet", "#A97EFE", "#8B5CF6"),
    BotColorDef("green", "Emerald", "#00C972", "#10B981"),
    BotColorDef("orange", "Forge Orange", "#FF781C", "#F97316"),
    BotColorDef("cyan", "Cyber Cyan", "#1CC3B0", "#06B6D4"),
    BotColorDef("blue", "Cobalt Blue", "#2A92FE", "#3B82F6"),
    BotColorDef("yellow", "Amber Gold", "#FFAF38", "#EAB308"),
    BotColorDef("brown", "Caramel Bronze", "#A27952", "#8D6E63"),
    BotColorDef("red", "Crimson Red", "#FF3E51", "#EF4444"),
    BotColorDef("magenta", "Neon Pink", "#FF5EB1", "#EC4899"),
    BotColorDef("gray", "Slate Silver", "#94A3B8", "#64748B")
)

private const val FALLBACK_RAKAZO_HEX = "#A97EFE" // Executive Violet, DEFAULT_BOT_AVATAR_COLOR

/** FNV-1a, as in shippedHash (bot-avatar-colors.ts). */
private fun shippedHash(value: String): Int {
    var hash = -2128831035 // 2166136261 as a signed 32-bit int
    for (ch in value) {
        // Int * wraps on overflow, exactly like JS Math.imul.
        hash = (hash xor ch.code) * 16777619
    }
    return hash
}

private fun shippedRandom(seed: Int): () -> Double {
    var value = seed
    return {
        value += 1831565813
        var next = (value xor (value ushr 15)) * (1 or value)
        next = (next + (next xor (next ushr 7)) * (61 or next)) xor next
        ((next xor (next ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

/**
 * The bot's one colour, as resolvePersonaColorDef picks it: a palette
 * colour by id/name/hex (old hexes resolve to their current value), any
 * other hex as-is, otherwise a seeded pick from the bot id.
 */
fun resolveRakazoColor(identity: String, explicit: String?): String {
    if (!explicit.isNullOrEmpty()) {
        val clean = explicit.lowercase()
        BOT_COLOR_LIST.firstOrNull { it.id == clean || it.name.lowercase() == clean }?.let { return it.hex }
        BOT_COLOR_LIST.firstOrNull {
            it.hex.lowercase() == clean || it.previousHex?.lowercase() == clean
        }?.let { return it.hex }
        if (HEX_RE.matches(explicit)) return expandHex(explicit).uppercase()
    }
    val k = -1640531527 // 2654435769 as a signed 32-bit int
    val seed = shippedHash(identity) xor k
    val index = (shippedRandom(seed xor k)() * BOT_COLOR_LIST.size).toInt()
    return BOT_COLOR_LIST[index % BOT_COLOR_LIST.size].hex
}

/** WCAG relative luminance. */
private fun relativeLuminance(color: Int): Double {
    fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) +
        0.0722 * channel(Color.blue(color))
}

fun contrastRatio(a: Int, b: Int): Double {
    val (high, low) = listOf(relativeLuminance(a), relativeLuminance(b)).sortedDescending()
    return (high + 0.05) / (low + 0.05)
}

/**
 * Which ink a bot's eyes take (botEyeTone): pale bodies get dark ink,
 * dark bodies get light ink, mid tones get dark ink too — the official
 * app cuts mid-tone eyes out in the page colour instead.
 */
fun eyeInkForBody(bodyColor: Int): Int {
    if (contrastRatio(bodyColor, Color.WHITE) < 2.4) return Color.parseColor("#12263F")
    if (contrastRatio(bodyColor, Color.BLACK) < 2.0) return Color.WHITE
    return Color.parseColor("#12263F")
}

// ---- MausBot colours (MAUS_COLORS, src/lib/mascot.ts) ----

val MAUS_COLORS: Map<String, String> = mapOf(
    "green" to "#009957", "blue" to "#377FE6", "red" to "#D94B52",
    "orange" to "#E78531", "purple" to "#8057C8", "cyan" to "#0EA5C6",
    "pink" to "#D84F8B", "yellow" to "#D8A729", "teal" to "#01A492",
    "coral" to "#E5634E", "silver" to "#A8ADB4", "chrome" to "#B3BAC4"
)

private fun mixHex(a: Int, b: Int, t: Float): Int {
    fun ch(shift: Int): Int {
        val va = (a shr shift) and 0xff
        val vb = (b shr shift) and 0xff
        return (va + (vb - va) * t).toInt().coerceIn(0, 255)
    }
    return Color.rgb(ch(16), ch(8), ch(0))
}

/** The mascot's 3-stop gradient (gradientFor, Avatar.tsx). */
fun mausGradient(colorName: String): IntArray {
    if (colorName == "chrome") {
        return intArrayOf(
            Color.parseColor("#F1F4F7"),
            Color.parseColor("#AEB8C3"),
            Color.parseColor("#66717E")
        )
    }
    val fill = parseHexColor(MAUS_COLORS[colorName] ?: "") ?: Color.parseColor("#009957")
    return intArrayOf(mixHex(fill, Color.WHITE, 0.55f), fill, mixHex(fill, Color.BLACK, 0.42f))
}

// ---- Avatar specs ----

sealed class AvatarSpec {
    /** Rakazo custom picture: a data:image/... URL carried in `color`. */
    data class PhotoData(val dataUrl: String) : AvatarSpec()

    /** Rakazo shipped mascot shape (0..7) in the bot's colour. */
    data class Shape(val color: Int, val shapeIndex: Int) : AvatarSpec()

    /** Rakazo logo mark in the bot's colour. */
    data class Logo(val color: Int) : AvatarSpec()

    /** Rakazo organic blob, seeded by the bot id. */
    data class Organic(val color: Int, val identity: String) : AvatarSpec()

    /** MausBot custom picture, fetched from the server. */
    data class MausPhoto(
        val url: String, val crop: String, val zoom: Float, val focusX: Float, val focusY: Float
    ) : AvatarSpec()

    /** MausBot gradient mouse mascot. */
    data class MausMascot(val colorName: String, val bodyId: String?, val faceColor: String?) : AvatarSpec()
}

private val AVATAR_URL_RE = Regex("^/api/attachments/[A-Za-z0-9-]+\\.(png|jpg|gif|webp)$")

/** The avatar this bot wears, in the same order the official apps pick it. */
fun BotItem.avatarSpec(): AvatarSpec {
    return when (kind) {
        ServerKind.RAKAZO -> {
            val raw = colorHex ?: ""
            when {
                raw.startsWith("data:image/") -> AvatarSpec.PhotoData(raw)
                raw.contains("::shape_") -> {
                    val parts = raw.split("::shape_")
                    val hex = parts.getOrNull(0) ?: ""
                    val idx = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val color = parseHexColor(resolveRakazoColor(id, hex))
                        ?: Color.parseColor(FALLBACK_RAKAZO_HEX)
                    AvatarSpec.Shape(color, ((idx % 8) + 8) % 8)
                }
                avatarSet == "rakazo" -> {
                    val color = parseHexColor(resolveRakazoColor(id, raw))
                        ?: Color.parseColor(FALLBACK_RAKAZO_HEX)
                    AvatarSpec.Logo(color)
                }
                else -> {
                    val color = parseHexColor(resolveRakazoColor(id, raw))
                        ?: Color.parseColor(FALLBACK_RAKAZO_HEX)
                    AvatarSpec.Organic(color, id)
                }
            }
        }
        ServerKind.MAUSBOT -> {
            val url = avatarUrl
            val crop = when (avatarCrop) {
                "circle", "rounded", "square" -> avatarCrop
                else -> "mascot"
            }
            if (url != null && AVATAR_URL_RE.matches(url) && crop != "mascot") {
                AvatarSpec.MausPhoto(url, crop, avatarZoom, avatarFocusX, avatarFocusY)
            } else {
                AvatarSpec.MausMascot(colorHex ?: "green", mascotBody, mascotFaceColor)
            }
        }
    }
}

// ---- Rakazo organic blob (avatar-shape.ts, ported) ----

fun avatarIdentitySeed(identity: String): Int {
    var hash = 0
    for (ch in identity) {
        hash = (hash shl 5) - hash + ch.code
    }
    return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else abs(hash)
}

private fun organicTemplates(): List<List<Pair<Double, Double>>> {
    val t0 = listOf(
        -43.0 to 22.0, -51.0 to 16.0, -53.0 to 6.0, -49.0 to -4.0, -40.0 to -12.0,
        -30.0 to -13.0, -27.0 to -24.0, -18.0 to -34.0, -6.0 to -36.0, 4.0 to -32.0,
        12.0 to -42.0, 25.0 to -45.0, 37.0 to -38.0, 43.0 to -27.0, 42.0 to -17.0,
        51.0 to -11.0, 56.0 to 0.0, 54.0 to 12.0, 46.0 to 20.0, 30.0 to 24.0,
        0.0 to 25.0, -29.0 to 25.0
    )
    val t1 = listOf(
        0.0 to -52.0, 12.0 to -35.0, 28.0 to -17.0, 39.0 to 2.0, 42.0 to 21.0,
        34.0 to 38.0, 19.0 to 49.0, 0.0 to 52.0, -19.0 to 49.0, -34.0 to 38.0,
        -42.0 to 21.0, -39.0 to 2.0, -28.0 to -17.0, -12.0 to -35.0
    )
    val t2 = listOf(
        -42.0 to -23.0, -27.0 to -40.0, -7.0 to -46.0, 14.0 to -40.0, 33.0 to -27.0,
        46.0 to -8.0, 46.0 to 14.0, 35.0 to 34.0, 14.0 to 46.0, -8.0 to 46.0,
        -29.0 to 38.0, -43.0 to 21.0, -48.0 to 0.0
    )
    fun ring(n: Int, radius: (Double) -> Double): List<Pair<Double, Double>> {
        return (0 until n).map { i ->
            val a = (i.toDouble() / n) * Math.PI * 2
            cos(a) * radius(a) to sin(a) * radius(a)
        }
    }
    val t3 = ring(40) { a -> 44 + cos(a * 5) * 5 }
    val t4 = ring(36) { a -> 43 + cos(a * 6) * 7 }
    val t5 = (0 until 32).map { i ->
        val a = (i.toDouble() / 32) * Math.PI * 2
        val x = cos(a)
        val y = sin(a)
        (if (x >= 0) 1.0 else -1.0) * Math.sqrt(abs(x)) * 46 to
            (if (y >= 0) 1.0 else -1.0) * Math.sqrt(abs(y)) * 43
    }
    val t6 = listOf(
        0.0 to 48.0, -14.0 to 36.0, -32.0 to 20.0, -45.0 to 0.0, -43.0 to -20.0,
        -29.0 to -36.0, -10.0 to -39.0, 0.0 to -27.0, 10.0 to -39.0, 29.0 to -36.0,
        43.0 to -20.0, 45.0 to 0.0, 32.0 to 20.0, 14.0 to 36.0
    )
    val t7 = listOf(
        -48.0 to 35.0, -38.0 to 22.0, -34.0 to -8.0, -27.0 to -31.0, -12.0 to -44.0,
        0.0 to -48.0, 12.0 to -44.0, 27.0 to -31.0, 34.0 to -8.0, 38.0 to 22.0,
        48.0 to 35.0, 26.0 to 42.0, 0.0 to 44.0, -26.0 to 42.0
    )
    val t8 = listOf(
        -45.0 to -19.0, -28.0 to -38.0, -4.0 to -46.0, 22.0 to -41.0, 42.0 to -25.0,
        49.0 to -2.0, 42.0 to 23.0, 22.0 to 42.0, -4.0 to 47.0, -29.0 to 38.0,
        -46.0 to 18.0, -50.0 to 0.0
    )
    val t9 = listOf(
        -50.0 to 0.0, -45.0 to -20.0, -30.0 to -38.0, 0.0 to -48.0, 30.0 to -38.0,
        45.0 to -20.0, 50.0 to 0.0, 35.0 to 10.0, 18.0 to 12.0, 16.0 to 38.0,
        0.0 to 46.0, -16.0 to 38.0, -18.0 to 12.0, -35.0 to 10.0
    )
    return listOf(t0, t1, t2, t3, t4, t5, t6, t7, t8, t9)
}

/** The organic body path for a seed, as an SVG path string (organicAvatarPath). */
fun organicAvatarPath(seed: Int, phaseOffset: Double = 0.0): String {
    val phase = ((seed % 360) * Math.PI) / 180 + phaseOffset
    val family = seed % 10
    val templates = organicTemplates()
    val xScale = 1 + sin(phase) * 0.035
    val yScale = 1 + cos(phase) * 0.025
    val shear = sin(phase * 1.7) * 0.025
    var points = (templates.getOrNull(family) ?: templates[5]).map { (x, y) ->
        (x * xScale + y * shear) to (y * yScale)
    }
    val passes = if (family in 3..5) 1 else 2
    repeat(passes) {
        points = points.flatMapIndexed { i, p ->
            val n = points[(i + 1) % points.size]
            listOf(
                (p.first * 0.75 + n.first * 0.25) to (p.second * 0.75 + n.second * 0.25),
                (p.first * 0.25 + n.first * 0.75) to (p.second * 0.25 + n.second * 0.75)
            )
        }
    }
    fun round(v: Double): Double = Math.round(v * 100) / 100.0
    fun num(v: Double): String {
        val r = round(v)
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
    val n = points.size
    val first = points[0]
    val sb = StringBuilder("M${num(first.first)} ${num(first.second)}")
    for (i in 0 until n) {
        val before = points[(i - 1 + n) % n]
        val cur = points[i]
        val next = points[(i + 1) % n]
        val after = points[(i + 2) % n]
        sb.append("C${num(cur.first + (next.first - before.first) / 6)} ${num(cur.second + (next.second - before.second) / 6)} ")
        sb.append("${num(next.first - (after.first - cur.first) / 6)} ${num(next.second - (after.second - cur.second) / 6)} ")
        sb.append("${num(next.first)} ${num(next.second)}")
    }
    sb.append("Z")
    return sb.toString()
}

fun organicAvatarEyeTilt(seed: Int): Float = ((seed % 9) - 4).toFloat()

// ---- The view ----

/**
 * Draws one bot's avatar. Vector specs draw immediately; photos are set
 * with setBitmap once AvatarLoader has fetched and decoded them.
 */
class BotAvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var spec: AvatarSpec? = null
    private var bitmap: Bitmap? = null
    private var bitmapCrop = "circle"
    private var bitmapZoom = 1f
    private var bitmapFocusX = 0.5f
    private var bitmapFocusY = 0.5f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setSpec(spec: AvatarSpec) {
        this.spec = spec
        bitmap = null
        invalidate()
    }

    fun setBitmap(bitmap: Bitmap, crop: String, zoom: Float, focusX: Float, focusY: Float) {
        this.bitmap = bitmap
        bitmapCrop = crop
        bitmapZoom = zoom.coerceIn(1f, 3f)
        bitmapFocusX = focusX.coerceIn(0f, 1f)
        bitmapFocusY = focusY.coerceIn(0f, 1f)
        invalidate()
    }

    fun clear() {
        spec = null
        bitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        bitmap?.let { drawPhoto(canvas, it, w, h); return }
        when (val s = spec) {
            is AvatarSpec.Shape -> drawShape(canvas, s, w, h)
            is AvatarSpec.Logo -> drawLogo(canvas, s, w, h)
            is AvatarSpec.Organic -> drawOrganic(canvas, s, w, h)
            is AvatarSpec.MausMascot -> drawMascot(canvas, s, w, h)
            else -> Unit // PhotoData / MausPhoto draw once the bitmap arrives.
        }
    }

    /** Maps a square viewBox (origin vbX/vbY, side vbSize) onto this view. */
    private fun viewBoxMatrix(vbX: Float, vbY: Float, vbSize: Float, w: Float, h: Float): Matrix {
        val s = minOf(w, h) / vbSize
        return Matrix().apply {
            setTranslate((w - vbSize * s) / 2 - vbX * s, (h - vbSize * s) / 2 - vbY * s)
            postScale(s, s)
        }
    }

    private fun drawPhoto(canvas: Canvas, bmp: Bitmap, w: Float, h: Float) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0 || bh <= 0) return
        val scale = maxOf(w / bw, h / bh) * bitmapZoom
        val dx = w / 2 - bitmapFocusX * bw * scale
        val dy = h / 2 - bitmapFocusY * bh * scale
        val m = Matrix().apply {
            setTranslate(dx, dy)
            postScale(scale, scale)
        }
        canvas.save()
        when (bitmapCrop) {
            "circle" -> {
                val p = Path().apply { addCircle(w / 2, h / 2, minOf(w, h) / 2, Path.Direction.CW) }
                canvas.clipPath(p)
            }
            "rounded" -> {
                val r = minOf(w, h) * 0.22f
                val p = Path().apply {
                    addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
                }
                canvas.clipPath(p)
            }
            else -> Unit // "square": no clip
        }
        canvas.drawBitmap(bmp, m, bitmapPaint)
        canvas.restore()
    }

    private fun drawBarEyes(canvas: Canvas, leftCx: Float, rightCx: Float, cy: Float, ew: Float, eh: Float) {
        val r = ew / 2
        for (cx in listOf(leftCx, rightCx)) {
            canvas.drawRoundRect(RectF(cx - ew / 2, cy - eh / 2, cx + ew / 2, cy + eh / 2), r, r, fillPaint)
        }
    }

    private fun drawShape(canvas: Canvas, s: AvatarSpec.Shape, w: Float, h: Float) {
        val path = shapePath(s.shapeIndex)
        canvas.save()
        canvas.concat(viewBoxMatrix(-15f, -15f, 259f, w, h))
        fillPaint.color = s.color
        canvas.drawPath(path, fillPaint)
        // The shipped shapes wear the same bar eyes (SHIPPED_BOT_AVATAR_EYES).
        fillPaint.color = eyeInkForBody(s.color)
        drawBarEyes(canvas, 91.7705f, 136.7705f, 106.2705f, 15f, 52f)
        canvas.restore()
    }

    private fun drawLogo(canvas: Canvas, s: AvatarSpec.Logo, w: Float, h: Float) {
        canvas.save()
        canvas.concat(viewBoxMatrix(0f, 0f, 100f, w, h))
        // Rounded plate, then the body clipped to it (the body runs past y=100).
        val plate = Path().apply {
            addRoundRect(RectF(0f, 0f, 100f, 100f), 23f, 23f, Path.Direction.CW)
        }
        fillPaint.color = Color.WHITE
        canvas.drawPath(plate, fillPaint)
        canvas.clipPath(plate)
        canvas.save()
        canvas.translate(0f, 9f) // LOGO_AVATAR_BODY_Y
        fillPaint.color = s.color
        canvas.drawPath(logoBodyPath(), fillPaint)
        fillPaint.color = eyeInkForBody(s.color)
        // Oval eyes (LOGO_AVATAR_EYES): rx 5.2, ry 8 at cy 40 in body coords.
        for (cx in listOf(40.5f, 59.5f)) {
            canvas.drawOval(RectF(cx - 5.2f, 32f, cx + 5.2f, 48f), fillPaint)
        }
        canvas.restore()
        canvas.restore()
    }

    private fun drawOrganic(canvas: Canvas, s: AvatarSpec.Organic, w: Float, h: Float) {
        val seed = avatarIdentitySeed(s.identity)
        val path = organicPath(seed)
        canvas.save()
        canvas.concat(viewBoxMatrix(-60f, -60f, 120f, w, h))
        fillPaint.color = s.color
        canvas.drawPath(path, fillPaint)
        fillPaint.color = eyeInkForBody(s.color)
        canvas.save()
        canvas.rotate(organicAvatarEyeTilt(seed), 0f, 0f)
        // Organic eyes (ORGANIC_AVATAR_EYES): 7x24 bars at cx -10.5 / 10.5, cy 0.
        drawBarEyes(canvas, -10.5f, 10.5f, 0f, 7f, 24f)
        canvas.restore()
        canvas.restore()
    }

    private fun drawMascot(canvas: Canvas, s: AvatarSpec.MausMascot, w: Float, h: Float) {
        val body = AvatarData.mascotBody(s.bodyId)
        val path = mascotBodyPath(body)
        val gradient = mausGradient(s.colorName)
        canvas.save()
        canvas.concat(viewBoxMatrix(-15f, -15f, 258.541f, w, h))
        // The gradient runs top-right to bottom-left across the body's bounds
        // (linearGradient x1=1 y1=0 x2=0 y2=1, stops 0 / 55% / 100%).
        val bounds = RectF()
        path.computeBounds(bounds, true)
        fillPaint.shader = LinearGradient(
            bounds.right, bounds.top, bounds.left, bounds.bottom,
            gradient, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null
        // Idle face: the two eye rings, placed by the body's anchor.
        fillPaint.color = mascotFaceInk(s)
        for (ring in AvatarData.idleEyes) {
            val eye = Path()
            val pts = ring.points
            eye.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) eye.lineTo(pts[i].first, pts[i].second)
            eye.close()
            eye.transform(anchorMatrix(body))
            canvas.drawPath(eye, fillPaint)
        }
        canvas.restore()
    }

    /** Face ink: an explicit white/black override, else a contrasting ink. */
    private fun mascotFaceInk(s: AvatarSpec.MausMascot): Int {
        when (s.faceColor) {
            "white" -> return Color.WHITE
            "black" -> return Color.BLACK
        }
        if (s.colorName == "chrome") return Color.parseColor("#1E2833")
        val fill = parseHexColor(MAUS_COLORS[s.colorName] ?: "") ?: Color.parseColor("#009957")
        return if (contrastRatio(fill, Color.BLACK) < 2.0) Color.WHITE else Color.parseColor("#1E2833")
    }

    companion object {
        private val shapeCache = HashMap<Int, Path>()
        private val organicCache = HashMap<Int, Path>()
        private val mascotBodyCache = HashMap<String, Path>()
        private val logoCache = arrayOfNulls<Path>(1)

        private fun shapePath(index: Int): Path {
            return shapeCache.getOrPut(index) {
                SvgPath.parse(AvatarData.rakazoShapes[index % AvatarData.rakazoShapes.size])
            }
        }

        private fun logoBodyPath(): Path {
            return logoCache[0] ?: SvgPath.parse(AvatarData.rakazoLogoBody).also { logoCache[0] = it }
        }

        private fun organicPath(seed: Int): Path {
            return organicCache.getOrPut(seed) { SvgPath.parse(organicAvatarPath(seed)) }
        }

        private fun mascotBodyPath(body: AvatarData.MascotBody): Path {
            return mascotBodyCache.getOrPut(body.id) {
                val p = SvgPath.parse(body.path)
                // fit: translate(fitX, fitY) scale(fitScale)
                val m = Matrix().apply {
                    setTranslate(body.fitTx, body.fitTy)
                    postScale(body.fitScale, body.fitScale)
                }
                p.transform(m)
                p
            }
        }

        /** anchorTransform: translate(anchor) scale(s) translate(-FACE_CENTRE). */
        private fun anchorMatrix(body: AvatarData.MascotBody): Matrix {
            return Matrix().apply {
                setTranslate(body.anchorX, body.anchorY)
                postScale(body.anchorScale, body.anchorScale)
                postTranslate(-120f, -122.5f)
            }
        }
    }
}

// ---- Async loading ----

/**
 * Resolves a bot's avatar into a view. Vector specs draw immediately.
 * Photos fetch on a background thread (Rakazo data URLs are decoded,
 * MausBot pictures come from GET /api/attachments/<name> with the
 * pairing token) and replace an interim vector avatar when they land;
 * a failed fetch simply keeps the interim avatar.
 */
object AvatarLoader {
    private val bg: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(context: Context, bot: BotItem, view: BotAvatarView) {
        val spec = bot.avatarSpec()
        when (spec) {
            is AvatarSpec.PhotoData -> {
                val color = parseHexColor(resolveRakazoColor(bot.id, bot.colorHex))
                    ?: Color.parseColor(FALLBACK_RAKAZO_HEX)
                view.setSpec(AvatarSpec.Organic(color, bot.id))
                val key = "avatar-rakazo:${bot.id}"
                view.tag = key
                bg.execute {
                    try {
                        val cached = ImageStore.get(key)
                        val bytes = cached ?: run {
                            val b64 = spec.dataUrl.substringAfter(",", "")
                            val raw = Base64.decode(b64, Base64.DEFAULT)
                            ImageStore.put(key, raw)
                            raw
                        }
                        val bmp = decodeSampled(bytes, 256) ?: return@execute
                        mainHandler.post {
                            if (view.tag == key) view.setBitmap(bmp, "circle", 1f, 0.5f, 0.5f)
                        }
                    } catch (e: Exception) {
                        // Keep the interim blob.
                    }
                }
            }
            is AvatarSpec.MausPhoto -> {
                view.setSpec(AvatarSpec.MausMascot(bot.colorHex ?: "green", bot.mascotBody, bot.mascotFaceColor))
                val name = spec.url.substringAfterLast('/')
                val key = "avatar-mausbot:$name"
                view.tag = key
                bg.execute {
                    try {
                        val cached = ImageStore.get(key)
                        val bytes = cached ?: run {
                            val base = Prefs.baseUrl(context, ServerKind.MAUSBOT)
                                ?: return@execute
                            val token = SecureStore.get(context, SecureStore.KEY_MAUSBOT_TOKEN)
                                ?: return@execute
                            val raw = MausBotClient(base).attachmentBytes(token, spec.url)
                            ImageStore.put(key, raw)
                            raw
                        }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                        mainHandler.post {
                            if (view.tag == key) {
                                view.setBitmap(bmp, spec.crop, spec.zoom, spec.focusX, spec.focusY)
                            }
                        }
                    } catch (e: Exception) {
                        // Keep the interim mascot.
                    }
                }
            }
            else -> {
                view.tag = null
                view.setSpec(spec)
            }
        }
    }
}
