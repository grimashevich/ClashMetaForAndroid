package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.store.UiStore

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context) {

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    var state: ProxyViewState? = null
    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        state.paint.apply {
            reset()

            textSize = state.config.textSize

            getTextBounds("Stub!", 0, 1, state.rect)
        }

        val textHeight = state.rect.height()
        val exceptHeight = (state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                textHeight * 2 +
                state.config.textMargin).toInt()

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidate()

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()

        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            if (state.config.proxyLine==1) {
                drawRect(0f, 0f, width, height, paint)
            } else {
                val path = state.path

                path.reset()

                path.addRoundRect(
                    state.config.layoutPadding,
                    state.config.layoutPadding,
                    width - state.config.layoutPadding,
                    height - state.config.layoutPadding,
                    state.config.cardRadius,
                    state.config.cardRadius,
                    Path.Direction.CW,
                )

                paint.setShadowLayer(
                    state.config.cardRadius,
                    state.config.cardOffset,
                    state.config.cardOffset,
                    state.config.shadow
                )

                drawPath(path, paint)

                clipPath(path)
            }
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        val height = height.toFloat()

        paint.textSize = state.config.textSize

        // measure delay text bounds
        val delayCount = paint.breakText(
            state.delayText,
            false,
            (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
                .coerceAtLeast(0f),
            null
        )

        state.paint.getTextBounds(state.delayText, 0, delayCount, state.rect)

        val delayWidth = state.rect.width()

        // fleet badges live between the labels and the delay number, so
        // they eat into the width the labels may use
        val badgeSize = state.config.badgeSize
        val badgeGap = state.config.badgeGap
        val badgeCount = (if (state.youtubeBadge != FleetBadge.None) 1 else 0) +
                (if (state.geminiBadge != FleetBadge.None) 1 else 0)
        val badgesWidth = if (badgeCount == 0) {
            0f
        } else {
            badgeCount * badgeSize + (badgeCount - 1) * badgeGap + state.config.textMargin
        }

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth -
                badgesWidth -
                state.config.textMargin * 2
                )
            .coerceAtLeast(0f)

        // measure title text bounds
        val titleCount = paint.breakText(
            state.title,
            false,
            mainTextWidth,
            null,
        )

        // measure subtitle text bounds
        val subtitleCount = paint.breakText(
            state.subtitle,
            false,
            mainTextWidth,
            null,
        )

        // text draw measure
        val textOffset = (paint.descent() + paint.ascent()) / 2

        paint.reset()

        paint.textSize = state.config.textSize
        paint.isAntiAlias = true
        paint.color = state.controls

        // draw delay
        canvas.apply {
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            val y = height / 2f - textOffset

            drawText(state.delayText, 0, delayCount, x, y, paint)
        }

        // draw title
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f - textOffset

            drawText(state.title, 0, titleCount, x, y, paint)
        }

        // draw subtitle
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset

            drawText(state.subtitle, 0, subtitleCount, x, y, paint)
        }

        // draw fleet badges (last: they reset the shared paint)
        if (badgeCount > 0) {
            val centerY = height / 2f
            var right = width -
                    state.config.layoutPadding -
                    state.config.contentPadding -
                    delayWidth -
                    state.config.textMargin

            if (state.geminiBadge != FleetBadge.None) {
                drawGeminiBadge(canvas, paint, state, right - badgeSize, centerY, badgeSize)

                right -= badgeSize + badgeGap
            }

            if (state.youtubeBadge != FleetBadge.None) {
                drawYoutubeBadge(canvas, paint, state, right - badgeSize, centerY, badgeSize)
            }
        }
    }

    /**
     * Coloured for an exit that behaves as foreign, muted grey for one
     * Google treats as Russian — the single rule behind both glyphs.
     */
    private fun badgeColor(state: ProxyViewState, badge: FleetBadge, brand: Int): Int {
        return if (badge == FleetBadge.Foreign) brand else state.config.mutedBadgeColor(state.controls)
    }

    /**
     * YouTube: a play triangle in an outlined rounded rectangle. Drawn as
     * an outline rather than a filled plate with a punched-out triangle
     * because the single-line layout leaves the row background
     * transparent, and a "cut-out" in a transparent row draws nothing.
     */
    private fun drawYoutubeBadge(
        canvas: Canvas,
        paint: Paint,
        state: ProxyViewState,
        left: Float,
        centerY: Float,
        size: Float,
    ) {
        val plateHeight = size * 0.74f
        val top = centerY - plateHeight / 2f
        val stroke = (size * 0.11f).coerceAtLeast(1f)
        val path = state.badgePath
        val color = badgeColor(state, state.youtubeBadge, state.config.youtubeBadgeColor)

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
    private fun drawGeminiBadge(
        canvas: Canvas,
        paint: Paint,
        state: ProxyViewState,
        left: Float,
        centerY: Float,
        size: Float,
    ) {
        val radius = size / 2f
        val centerX = left + radius
        val path = state.badgePath

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = badgeColor(state, state.geminiBadge, state.config.geminiBadgeColor)

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