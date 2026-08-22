package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterServerPriorityBinding
import com.github.kr328.clash.design.model.ServerPriorityEntry
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import java.util.Collections

class ServerPriorityAdapter(
    private val context: Context,
    val entries: MutableList<ServerPriorityEntry>,
) : RecyclerView.Adapter<ServerPriorityAdapter.Holder>() {
    class Holder(val binding: AdapterServerPriorityBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * Resolved once: [resolveThemedColor] walks the theme, and the badge
     * views need it on every bind to mute a Russian-looking exit.
     */
    private val controlColor = context.resolveThemedColor(android.R.attr.textColorPrimary)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterServerPriorityBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]

        holder.binding.position = (position + 1).toString()
        holder.binding.serverName = entry.name

        holder.binding.youtubeBadge.also {
            it.controlColor = controlColor
            it.badge = entry.youtube
        }

        holder.binding.geminiBadge.also {
            it.controlColor = controlColor
            it.badge = entry.gemini
        }
    }

    override fun getItemCount(): Int {
        return entries.size
    }

    /**
     * Moves a row during a drag.
     *
     * Only the move itself is announced. Every row between the two
     * endpoints also shifts by one, so their rank labels go stale — but
     * rebinding them here would fight the drag animation, and rebinding
     * the row currently under the finger would visibly reset it. The
     * labels are refreshed once, on drop, by [refreshRanks].
     */
    fun move(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from !in entries.indices || to !in entries.indices) return false

        if (from < to) {
            for (i in from until to) {
                Collections.swap(entries, i, i + 1)
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(entries, i, i - 1)
            }
        }

        notifyItemMoved(from, to)

        return true
    }

    /**
     * Rebinds every visible row so the rank numbers match the order
     * again. Called after a drag settles; cheap, since the rows are a
     * couple of TextViews and two small custom views.
     */
    fun refreshRanks() {
        notifyItemRangeChanged(0, itemCount)
    }

    /** Replaces the whole list, e.g. after "reset to subscription order". */
    fun replaceAll(newEntries: List<ServerPriorityEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
}
