package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.component.FleetBadge
import com.github.kr328.clash.design.component.FleetBadges

/**
 * Draws one fleet-status glyph in an ordinary layout.
 *
 * The proxy list paints its badges straight onto a custom-drawn card;
 * the server-priorities list is built from normal Views, so it needs a
 * View to hang one on. Both go through [FleetBadges], so the glyph is
 * the same drawing code in both places.
 *
 * [badge] `None` hides the view entirely rather than drawing a third,
 * "no data" symbol: on the proxy screen the absence of a badge already
 * means "the sweep has no answer for this node", and the two screens
 * must not disagree about what nothing means.
 */
class FleetBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    enum class Kind { Youtube, Gemini }

    private val paint = Paint()
    private val path = Path()

    var kind: Kind = Kind.Gemini
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The row's text colour, used to mute a Russian-looking exit. */
    var controlColor: Int = 0xFF000000.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var badge: FleetBadge = FleetBadge.None
        set(value) {
            if (field != value) {
                field = value
                visibility = if (value == FleetBadge.None) GONE else VISIBLE
                invalidate()
            }
        }

    init {
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.FleetBadgeView).apply {
                // 0 = youtube, 1 = gemini; matches the enum order in attrs.xml
                kind = if (getInt(R.styleable.FleetBadgeView_badgeKind, 1) == 0) {
                    Kind.Youtube
                } else {
                    Kind.Gemini
                }
                recycle()
            }
        }

        visibility = if (badge == FleetBadge.None) GONE else VISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        if (badge == FleetBadge.None) return

        // Square, centred, inset so the YouTube plate's stroke is not
        // clipped by the view bounds.
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        val left = (width - size) / 2f
        val centerY = height / 2f
        val brand = when (kind) {
            Kind.Youtube -> FleetBadges.YOUTUBE_COLOR
            Kind.Gemini -> FleetBadges.GEMINI_COLOR
        }
        val color = FleetBadges.colorFor(badge, brand, controlColor)

        when (kind) {
            Kind.Youtube -> FleetBadges.drawYoutube(canvas, paint, path, left, centerY, size, color)
            Kind.Gemini -> FleetBadges.drawGemini(canvas, paint, path, left, centerY, size, color)
        }
    }
}
