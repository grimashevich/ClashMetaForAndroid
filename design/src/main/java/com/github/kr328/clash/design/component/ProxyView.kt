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

        // Fleet badges stack *under* the delay number rather than beside
        // it: the right-hand column then costs max(delay, badges) instead
        // of their sum, which is what keeps server names from being
        // truncated to "Швейц" in the two- and three-column layouts.
        val badgeSize = state.config.badgeSize
        val badgeGap = state.config.badgeGap
        val badgeCount = (if (state.youtubeBadge != FleetBadge.None) 1 else 0) +
                (if (state.geminiBadge != FleetBadge.None) 1 else 0)
        val badgesWidth = if (badgeCount == 0) {
            0f
        } else {
            badgeCount * badgeSize + (badgeCount - 1) * badgeGap
        }

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth.toFloat().coerceAtLeast(badgesWidth) -
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

        // draw delay — on the title line when badges share the column,
        // vertically centred otherwise (the layout without fleet data is
        // exactly what it was before badges existed)
        canvas.apply {
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            val y = if (badgeCount == 0) {
                height / 2f - textOffset
            } else {
                state.config.layoutPadding +
                        (height - state.config.layoutPadding * 2) / 3f - textOffset
            }

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

        // draw fleet badges (last: they reset the shared paint), on the
        // subtitle line, right-aligned under the delay number
        if (badgeCount > 0) {
            // the subtitle line's optical centre: the text baselines are
            // offset from it by textOffset, a glyph is centred on it
            val centerY = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f * 2
            var right = width - state.config.layoutPadding - state.config.contentPadding

            if (state.geminiBadge != FleetBadge.None) {
                FleetBadges.drawGemini(
                    canvas, paint, state.badgePath,
                    right - badgeSize, centerY, badgeSize,
                    badgeColor(state, state.geminiBadge, state.config.geminiBadgeColor),
                )

                right -= badgeSize + badgeGap
            }

            if (state.youtubeBadge != FleetBadge.None) {
                FleetBadges.drawYoutube(
                    canvas, paint, state.badgePath,
                    right - badgeSize, centerY, badgeSize,
                    badgeColor(state, state.youtubeBadge, state.config.youtubeBadgeColor),
                )
            }
        }
    }

    /**
     * Coloured for an exit that behaves as foreign, muted grey for one
     * Google treats as Russian — the single rule behind both glyphs.
     */
    private fun badgeColor(state: ProxyViewState, badge: FleetBadge, brand: Int): Int {
        return FleetBadges.colorFor(badge, brand, state.controls)
    }
}