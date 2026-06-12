package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.ServerPriorityAdapter
import com.github.kr328.clash.design.databinding.DesignServerPrioritiesBinding
import com.github.kr328.clash.design.model.ServerPriorityEntry
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServerPrioritiesDesign(
    context: Context,
    entries: List<ServerPriorityEntry>,
) : Design<ServerPrioritiesDesign.Request>(context) {
    sealed class Request {
        data class Edit(val index: Int) : Request()
    }

    private val binding = DesignServerPrioritiesBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = ServerPriorityAdapter(context, entries) { index ->
        requests.trySend(Request.Edit(index))
    }

    override val root: View
        get() = binding.root

    val entries: List<ServerPriorityEntry>
        get() = adapter.entries

    suspend fun notifyChanged(index: Int) {
        withContext(Dispatchers.Main) {
            adapter.notifyItemChanged(index)
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.bindAppBarElevation(binding.activityBarLayout)
        binding.mainList.recyclerList.applyLinearAdapter(context, adapter)
    }
}
