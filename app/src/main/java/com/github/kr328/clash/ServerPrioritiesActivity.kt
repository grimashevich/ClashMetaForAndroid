package com.github.kr328.clash

import com.github.kr328.clash.design.ServerPrioritiesDesign
import com.github.kr328.clash.design.model.ServerPriorityEntry
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.util.ServerPriorityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import com.github.kr328.clash.design.R

class ServerPrioritiesActivity : BaseActivity<ServerPrioritiesDesign>() {
    override suspend fun main() {
        val entries = withContext(Dispatchers.IO) {
            val servers = ServerPriorityStore.loadKnownServers(this@ServerPrioritiesActivity)
            val priorities = ServerPriorityStore.loadPriorities(this@ServerPrioritiesActivity)

            servers.map { ServerPriorityEntry(it, priorities[it]) }
        }

        val design = ServerPrioritiesDesign(this, entries)

        setContentDesign(design)

        if (entries.isEmpty()) {
            design.showToast(R.string.server_priorities_empty, ToastDuration.Indefinite)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                }
                design.requests.onReceive {
                    when (it) {
                        is ServerPrioritiesDesign.Request.Edit -> {
                            val entry = design.entries[it.index]

                            val input = requestModelTextInput(
                                initial = entry.priority?.toString(),
                                title = entry.name,
                                reset = getString(R.string.reset),
                                hint = getString(R.string.server_priority_input_hint),
                                validator = { text ->
                                    text.isEmpty() || text.trim().toIntOrNull() != null
                                },
                            )

                            // null = Reset pressed; cancel returns `initial`,
                            // which parses back to the unchanged value
                            val newPriority = input?.trim()?.toIntOrNull()

                            if (newPriority != entry.priority) {
                                entry.priority = newPriority

                                design.notifyChanged(it.index)

                                withContext(Dispatchers.IO) {
                                    val priorities = design.entries
                                        .mapNotNull { e -> e.priority?.let { p -> e.name to p } }
                                        .toMap()

                                    ServerPriorityStore.savePriorities(
                                        this@ServerPrioritiesActivity,
                                        priorities
                                    )
                                }

                                // rebuilds the ⚡ fallback group if the
                                // service is currently running (same reload
                                // path as override edits)
                                sendOverrideChanged()
                            }
                        }
                    }
                }
            }
        }
    }
}
