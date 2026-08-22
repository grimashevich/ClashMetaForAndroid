package com.github.kr328.clash

import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.ServerPrioritiesDesign
import com.github.kr328.clash.design.component.FleetBadge
import com.github.kr328.clash.design.model.ServerPriorityEntry
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.util.ServerPriorityStore
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import com.github.kr328.clash.design.R

class ServerPrioritiesActivity : BaseActivity<ServerPrioritiesDesign>() {
    override suspend fun main() {
        // Order first, from disk, so the screen paints without waiting on
        // anything: it has to work with the VPN stopped, and binding the
        // service can take a moment even when it is running.
        val entries = loadOrder()

        val design = ServerPrioritiesDesign(this, entries)

        setContentDesign(design)

        if (entries.isEmpty()) {
            design.showToast(R.string.server_priorities_empty, ToastDuration.Indefinite)
        } else {
            applyBadges(design)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive { event ->
                    when (event) {
                        // Mirror the other settings screens: a service
                        // recreate or VPN start/stop may have re-written
                        // known_servers.json, so reload the list. It also
                        // brings the fleet badges back once the core is up.
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ServerPrioritiesDesign.Request.OrderChanged ->
                            persist(design, design.entries.map { e -> e.name })

                        ServerPrioritiesDesign.Request.Reset -> {
                            // Subscription order is exactly known_servers.json
                            // with no priorities applied.
                            val servers = withContext(Dispatchers.IO) {
                                ServerPriorityStore.loadKnownServers(this@ServerPrioritiesActivity)
                            }
                            val badges = design.entries.associateBy { e -> e.name }
                            val reset = servers.map { name ->
                                badges[name] ?: ServerPriorityEntry(name)
                            }

                            design.replaceAll(reset)

                            persist(design, servers)
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes the order and asks the running service to rebuild the
     * "⚡ Приоритет" group from it. On a write failure the screen is
     * reloaded from disk rather than left showing an order that was
     * never saved.
     */
    private suspend fun persist(design: ServerPrioritiesDesign, orderedNames: List<String>) {
        val saved = withContext(Dispatchers.IO) {
            ServerPriorityStore.saveOrder(this@ServerPrioritiesActivity, orderedNames)
        }

        if (saved) {
            // rebuilds the ⚡ fallback group if the service is currently
            // running (same reload path as override edits)
            sendOverrideChanged()
        } else {
            design.replaceAll(loadOrder())

            design.showToast(
                R.string.server_priorities_save_failed,
                ToastDuration.Long,
            )
        }
    }

    /**
     * The names and their order, straight from the two JSON files the Go
     * config layer shares with this screen. No badges — see [applyBadges].
     */
    private suspend fun loadOrder(): List<ServerPriorityEntry> {
        return withContext(Dispatchers.IO) {
            val servers = ServerPriorityStore.loadKnownServers(this@ServerPrioritiesActivity)
            val priorities = ServerPriorityStore.loadPriorities(this@ServerPrioritiesActivity)

            ServerPriorityStore.orderServers(servers, priorities)
                .map { ServerPriorityEntry(it) }
        }
    }

    /**
     * Overlays the fleet verdicts onto the rows already on screen.
     *
     * The verdicts come from the core rather than from fleet_status.json
     * directly, because the core is the only thing that knows how a feed
     * node name maps onto a proxy name. That matching is deliberately not
     * reimplemented here: a second copy of it would drift, which is
     * exactly how the schema 2 incident happened (docs/HARDENING_WORK.md).
     * With the core stopped the query yields nothing and the rows keep no
     * badges — the same thing "no verdict" looks like everywhere else.
     *
     * Handed to the design as a name-keyed overlay rather than as a new
     * list: replacing the list mid-drag desyncs the ItemTouchHelper
     * (review round 1, CRITICAL), so the design applies these in place
     * and holds them back while a drag is running.
     */
    private suspend fun applyBadges(design: ServerPrioritiesDesign) {
        val verdicts = runCatching {
            withClash {
                queryProxyGroup(PRIORITY_GROUP_NAME, ProxySort.Default)
            }.proxies.associateBy { it.name }
        }.getOrNull() ?: return

        if (verdicts.isEmpty()) return

        val updated = design.entries.mapNotNull { entry ->
            val proxy = verdicts[entry.name] ?: return@mapNotNull null

            // Same rule as ProxyViewState, character for character: a
            // verdict counts only for a real server the prober reached,
            // "unknown" is the absence of an answer rather than a Russian
            // verdict, and YouTube's attribution is a separate signal from
            // Gemini's. The isGroup guard is defensive — the injected
            // group only ever holds plain proxies — but the two screens
            // disagreeing about one server is the bug this whole mapping
            // exists to avoid, so the condition is kept literally identical.
            val hasVerdict = !proxy.isGroup && proxy.fleetReachable && proxy.fleetCheckedAt > 0

            entry.copy(
                gemini = when {
                    !hasVerdict -> FleetBadge.None
                    proxy.fleetGemini == "available" -> FleetBadge.Foreign
                    proxy.fleetGemini == "blocked" -> FleetBadge.Russian
                    else -> FleetBadge.None
                },
                youtube = when {
                    !hasVerdict || proxy.fleetYoutubeGl.isEmpty() -> FleetBadge.None
                    proxy.fleetYoutubeGl == "RU" -> FleetBadge.Russian
                    else -> FleetBadge.Foreign
                },
            )
        }

        design.applyBadges(updated.associateBy { it.name })
    }

    companion object {
        /**
         * The injected fallback group whose members carry the fleet
         * annotation. Defined by customGroupPriority in
         * core/src/main/golang/native/config/customgroups.go — the two
         * strings must match.
         */
        private const val PRIORITY_GROUP_NAME = "⚡ Приоритет"
    }
}
