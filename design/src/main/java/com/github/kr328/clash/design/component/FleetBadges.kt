package com.github.kr328.clash.design.component

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * The two fleet-status glyphs, in one place.
 *
 * They are drawn on more than one screen — the proxy list rows
 * ([ProxyView], a fully custom-drawn card) and the server-priorities rows
 * (ordinary Views, via [com.github.kr328.clash.design.view.FleetBadgeView]).
 * Those two callers have nothing else in common, so the shapes live here
 * as plain drawing functions rather than in either of them: a badge that
 * meant "YouTube" on one screen and looked different on the other would
 * defeat the point of having a badge at all.
 *
 * Everything is expressed relative to `size`, so a caller only decides
 * where and how big. Callers own the [Paint] and [Path] (both are reset
 * here before use, and the paint is left dirty afterwards) so that a
 * hot draw path can keep reusing its own instances.
 */
object FleetBadges {
    /** YouTube red and Gemini blue, used when the exit behaves as foreign. */
    const val YOUTUBE_COLOR = 0xFFFF0000.toInt()
    const val GEMINI_COLOR = 0xFF4285F4.toInt()

    /**
     * A Russian-looking exit keeps the shape but loses the colour. Built
     * from the row's own control colour so it stays legible on both the
     * selected and unselected background, in light and dark themes.
     */
    fun mutedColor(control: Int): Int = Color.argb(
        0x66,
        Color.red(control),
        Color.green(control),
        Color.blue(control),
    )

    /**
     * Coloured for an exit that behaves as foreign, muted for one Google
     * treats as Russian — the single rule behind both glyphs.
     */
    fun colorFor(badge: FleetBadge, brand: Int, control: Int): Int {
        return if (badge == FleetBadge.Foreign) brand else mutedColor(control)
    }

    /**
     * YouTube: a play triangle in an outlined rounded rectangle. Drawn as
     * an outline rather than a filled plate with a punched-out triangle
     * because the proxy row's single-line layout leaves the background
     * transparent, and a "cut-out" in a transparent row draws nothing.
     */
    fun drawYoutube(
        canvas: Canvas,
        paint: Paint,
        path: Path,
        left: Float,
        centerY: Float,
        size: Float,
        color: Int,
    ) {
        val plateHeight = size * 0.74f
        val top = centerY - plateHeight / 2f
        val stroke = (size * 0.11f).coerceAtLeast(1f)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = color

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke

        path.reset()
        path.addRoundRect(
            left + stroke / 2f,
            top + stroke / 2f,
            left + size - stroke / 2f,
            top + plateHeight - stroke / 2f,
            plateHeight * 0.3f,
            plateHeight * 0.3f,
            Path.Direction.CW,
        )

        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL

        val triangleHeight = plateHeight * 0.44f
        val triangleWidth = triangleHeight * 0.9f
        val triangleLeft = left + size / 2f - triangleWidth / 2f

        path.reset()
        path.moveTo(triangleLeft, centerY - triangleHeight / 2f)
        path.lineTo(triangleLeft + triangleWidth, centerY)
        path.lineTo(triangleLeft, centerY + triangleHeight / 2f)
        path.close()

        canvas.drawPath(path, paint)
    }

    /**
     * Gemini: the four-point sparkle. Each side is a quadratic whose
     * control point is the centre, which is what pulls the edges inward
     * into a star instead of leaving a diamond.
     */
    fun drawGemini(
        canvas: Canvas,
        paint: Paint,
        path: Path,
        left: Float,
        centerY: Float,
        size: Float,
        color: Int,
    ) {
        val radius = size / 2f
        val centerX = left + radius

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = color

        path.reset()
        path.moveTo(centerX, centerY - radius)
        path.quadTo(centerX, centerY, centerX + radius, centerY)
        path.quadTo(centerX, centerY, centerX, centerY + radius)
        path.quadTo(centerX, centerY, centerX - radius, centerY)
        path.quadTo(centerX, centerY, centerX, centerY - radius)
        path.close()

        canvas.drawPath(path, paint)
    }
}
