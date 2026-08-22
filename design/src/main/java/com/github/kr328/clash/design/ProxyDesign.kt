package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.adapter.ProxyAdapter
import com.github.kr328.clash.design.adapter.ProxyPageAdapter
import com.github.kr328.clash.design.component.ProxyMenu
import com.github.kr328.clash.design.component.ProxyViewConfig
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyDesign(
    context: Context,
    overrideMode: TunnelState.Mode?,
    groupNames: List<String>,
    uiStore: UiStore,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        object ReLaunch : Request()

        data class PatchMode(val mode: TunnelState.Mode?) : Request()
        data class Reload(val index: Int) : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int) : Request()
    }

    private val binding = DesignProxyBinding
        .inflate(context.layoutInflater, context.root, false)

    private var config = ProxyViewConfig(context, uiStore.proxyLine)

    private val menu: ProxyMenu by lazy {
        ProxyMenu(context, binding.menuView, overrideMode, uiStore, requests) {
            config.proxyLine = uiStore.proxyLine
        }
    }

    private val adapter: ProxyPageAdapter
        get() = binding.pagesView.adapter!! as ProxyPageAdapter

    private var horizontalScrolling = false
    private val verticalBottomScrolled: Boolean
        get() = adapter.states[binding.pagesView.currentItem].bottom
    private var urlTesting: Boolean
        get() = adapter.states[binding.pagesView.currentItem].urlTesting
        set(value) {
            adapter.states[binding.pagesView.currentItem].urlTesting = value
        }

    override val root: View = binding.root

    suspend fun updateGroup(
        position: Int,
        proxies: List<Proxy>,
        selectable: Boolean,
        parent: ProxyState,
        links: Map<String, ProxyState>
    ) {
        adapter.updateAdapter(position, proxies, selectable, parent, links)

        adapter.states[position].urlTesting = false

        updateUrlTestButtonStatus()
    }

    suspend fun requestRedrawVisible() {
        withContext(Dispatchers.Main) {
            adapter.requestRedrawVisible()
        }
    }

    suspend fun showModeSwitchTips() {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.mode_switch_tips, Toast.LENGTH_LONG).show()
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.menuView.setOnClickListener {
            menu.show()
        }

        if (groupNames.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE

            binding.urlTestView.visibility = View.GONE
            binding.tabLayoutView.visibility = View.GONE
            binding.elevationView.visibility = View.GONE
            binding.pagesView.visibility = View.GONE
            binding.urlTestFloatView.visibility = View.GONE
        } else {
            binding.urlTestFloatView.supportImageTintList = ColorStateList.valueOf(
                context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
            )

            binding.pagesView.apply {
                adapter = ProxyPageAdapter(
                    surface,
                    config,
                    List(groupNames.size) { index ->
                        ProxyAdapter(
                            config,
                            clicked = { name -> requests.trySend(Request.Select(index, name)) },
                            longClicked = ::showFleetDetails,
                        )
                    }
                ) {
                    if (it == currentItem)
                        updateUrlTestButtonStatus()
                }

                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrollStateChanged(state: Int) {
                        horizontalScrolling = state != ViewPager2.SCROLL_STATE_IDLE

                        updateUrlTestButtonStatus()
                    }

                    override fun onPageSelected(position: Int) {
                        uiStore.proxyLastGroup = groupNames[position]
                    }
                })
            }

            TabLayoutMediator(binding.tabLayoutView, binding.pagesView) { tab, index ->
                tab.text = groupNames[index]
            }.attach()

            val initialPosition = groupNames.indexOf(uiStore.proxyLastGroup)

            binding.pagesView.post {
                if (initialPosition > 0)
                    binding.pagesView.setCurrentItem(initialPosition, false)
            }
        }
    }

    /**
     * Long-press detail sheet for one server: what the hourly fleet sweep
     * saw from the outside — exit IP, the country YouTube attributes to
     * it, whether Gemini answers (with the refusal text, which names the
     * reason) and how long ago that was measured.
     *
     * The row itself only has room for two badges; everything the badges
     * compress away lives here.
     */
    private fun showFleetDetails(proxy: Proxy) {
        val message = if (proxy.isGroup || proxy.fleetCheckedAt <= 0) {
            context.getString(R.string.fleet_no_data)
        } else {
            buildString {
                if (proxy.fleetExitIp.isNotEmpty()) {
                    append(context.getString(R.string.fleet_exit_ip))
                    append(": ")
                    appendLine(proxy.fleetExitIp)
                }

                if (proxy.fleetYoutubeGl.isNotEmpty()) {
                    append(context.getString(R.string.fleet_youtube))
                    append(": ")
                    appendLine(proxy.fleetYoutubeGl)
                }

                val gemini = when (proxy.fleetGemini) {
                    "available" -> context.getString(R.string.fleet_gemini_available)
                    "blocked" -> context.getString(R.string.fleet_gemini_blocked)
                    // The sweep has no answer for this node: its own
                    // check could not run, or the last real verdict aged
                    // out. Not a refusal — hence no badge, and the
                    // balancer leaves the node where it already was.
                    "unknown" -> context.getString(R.string.fleet_gemini_unknown)
                    else -> proxy.fleetGemini
                }

                if (gemini.isNotEmpty()) {
                    append(context.getString(R.string.fleet_gemini))
                    append(": ")
                    appendLine(gemini)
                }

                if (proxy.fleetGeminiDetail.isNotEmpty()) {
                    appendLine(proxy.fleetGeminiDetail)
                }

                // A verdict can be older than the row that carries it:
                // since schema 2 a check that could not run keeps the
                // previous answer instead of erasing it. Only worth a
                // line when the two timestamps actually differ.
                val geminiAt = proxy.fleetGeminiCheckedAt
                if (geminiAt > 0 && proxy.fleetCheckedAt - geminiAt > 60) {
                    append(context.getString(R.string.fleet_gemini_measured))
                    append(": ")
                    appendLine(relativeTime(geminiAt))
                }

                if (!proxy.fleetReachable) {
                    appendLine(context.getString(R.string.fleet_unreachable))
                }

                append(context.getString(R.string.fleet_checked_at))
                append(": ")
                append(relativeTime(proxy.fleetCheckedAt))
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(proxy.title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Unix seconds -> "5 minutes ago", for the fleet detail sheet. */
    private fun relativeTime(unixSeconds: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            unixSeconds * 1000,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )

    fun requestUrlTesting() {
        urlTesting = true

        requests.trySend(Request.UrlTest(binding.pagesView.currentItem))

        updateUrlTestButtonStatus()
    }

    private fun updateUrlTestButtonStatus() {
        if (verticalBottomScrolled || horizontalScrolling || urlTesting) {
            binding.urlTestFloatView.hide()
        } else {
            binding.urlTestFloatView.show()
        }

        if (urlTesting) {
            binding.urlTestView.visibility = View.GONE
            binding.urlTestProgressView.visibility = View.VISIBLE
        } else {
            binding.urlTestView.visibility = View.VISIBLE
            binding.urlTestProgressView.visibility = View.GONE
        }
    }
}