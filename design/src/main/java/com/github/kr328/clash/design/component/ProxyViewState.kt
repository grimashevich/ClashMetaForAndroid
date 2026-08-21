package com.github.kr328.clash.design.component

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.model.ProxyState
import kotlin.math.absoluteValue
import kotlin.math.max

/**
 * How a fleet-status badge is painted: [Foreign] = the exit behaves like
 * a non-Russian one (coloured), [Russian] = Google treats it as Russian
 * (grey), [None] = no fresh verdict, so nothing is drawn at all rather
 * than a third state the user would have to decode.
 */
enum class FleetBadge {
    None, Foreign, Russian
}

class ProxyViewState(
    val config: ProxyViewConfig,
    val proxy: Proxy,
    private val parent: ProxyState,
    private val link: ProxyState?
) {
    val paint = Paint()
    val rect = Rect()
    val path = Path()

    /** Separate from [path] so badge geometry cannot disturb the card clip. */
    val badgePath = Path()

    /**
     * Fleet verdicts are per-server facts that never change for the life
     * of a row (a refresh rebuilds the list), so they are resolved once
     * here instead of on every frame.
     *
     * A verdict counts only when the prober actually reached the node;
     * Go has already dropped anything staler than its freshness window,
     * so empty fields here mean "unknown", not "old".
     */
    private val hasVerdict = !proxy.isGroup && proxy.fleetReachable && proxy.fleetCheckedAt > 0

    /**
     * Gemini is the primary signal: it answers only for non-Russian
     * exits, so "available" is exactly "this exit behaves as foreign".
     */
    val geminiBadge: FleetBadge = when {
        !hasVerdict -> FleetBadge.None
        proxy.fleetGemini == "available" -> FleetBadge.Foreign
        proxy.fleetGemini == "blocked" -> FleetBadge.Russian
        else -> FleetBadge.None
    }

    /**
     * YouTube's own attribution, which can disagree with Gemini (an exit
     * can be geolocated to RU by YouTube while Gemini still answers).
     * Shown as its own badge rather than folded into the classification.
     */
    val youtubeBadge: FleetBadge = when {
        !hasVerdict || proxy.fleetYoutubeGl.isEmpty() -> FleetBadge.None
        proxy.fleetYoutubeGl == "RU" -> FleetBadge.Russian
        else -> FleetBadge.Foreign
    }

    var title: String = ""
    var subtitle: String = ""
    var delayText: String = ""
    var background: Int = config.unselectedBackground
    var controls: Int = config.unselectedControl

    private var delay: Int = 0
    private var selected: Boolean = false
    private var parentNow: String = ""
    private var linkNow: String? = null

    private var lastFrameTime = System.currentTimeMillis()

    fun update(snap: Boolean): Boolean {
        val frameTime = System.currentTimeMillis()
        var invalidate = false

        if (proxy.isGroup) {
            title = proxy.name

            if (link == null) {
                subtitle = proxy.type
            } else {
                if (linkNow !== link.now) {
                    linkNow = link.now

                    subtitle = "%s(%s)".format(
                        proxy.type,
                        link.now.ifEmpty { "*" }
                    )
                }
            }
        } else {
            title = proxy.title
            subtitle = proxy.subtitle
        }

        if (delay != proxy.delay) {
            delay = proxy.delay
            delayText = if (proxy.delay in 0..Short.MAX_VALUE) proxy.delay.toString() else ""
        }

        if (parentNow !== parent.now) {
            parentNow = parent.now
            selected = proxy.name == parent.now
        }

        controls = if (selected) config.selectedControl else config.unselectedControl

        if (snap) {
            background = if (selected) config.selectedBackground else config.unselectedBackground
        } else {
            val target = if (selected) config.selectedBackground else config.unselectedBackground

            if (background != target) {
                val sa = Color.alpha(background)
                val sr = Color.red(background)
                val sg = Color.green(background)
                val sb = Color.blue(background)

                val ta = Color.alpha(target)
                val tr = Color.red(target)
                val tg = Color.green(target)
                val tb = Color.blue(target)

                val da = ta - sa
                val dr = tr - sr
                val dg = tg - sg
                val db = tb - sb

                val max = max(
                    da.absoluteValue,
                    max(
                        dr.absoluteValue,
                        max(
                            dg.absoluteValue,
                            db.absoluteValue
                        )
                    )
                )

                val frameOffset = frameTime - lastFrameTime

                val colorOffset = (frameOffset / max.toFloat().coerceAtLeast(0.001f))
                    .coerceIn(0.0f, 1.0f)

                background = if (colorOffset > 0.999f) {
                    target
                } else {
                    Color.argb(
                        (sa + da * colorOffset).toInt(),
                        (sr + dr * colorOffset).toInt(),
                        (sg + dg * colorOffset).toInt(),
                        (sb + db * colorOffset).toInt()
                    )
                }

                invalidate = true
            }
        }

        lastFrameTime = frameTime

        return invalidate
    }
}