package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterServerPriorityBinding
import com.github.kr328.clash.design.model.ServerPriorityEntry
import com.github.kr328.clash.design.util.layoutInflater

class ServerPriorityAdapter(
    private val context: Context,
    val entries: List<ServerPriorityEntry>,
    private val onEdit: (Int) -> Unit,
) : RecyclerView.Adapter<ServerPriorityAdapter.Holder>() {
    class Holder(val binding: AdapterServerPriorityBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterServerPriorityBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]

        holder.binding.serverName = entry.name
        holder.binding.priorityText = entry.priority?.toString() ?: "—"
        holder.binding.edit = android.view.View.OnClickListener {
            onEdit(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount(): Int {
        return entries.size
    }
}
