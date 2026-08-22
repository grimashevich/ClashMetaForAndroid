package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
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
        /**
         * Sent once when a drag finishes, not on every row swap: the
         * order is only worth persisting (and worth a config reload)
         * once the user has let go.
         */
        object OrderChanged : Request()

        object Reset : Request()
    }

    private val binding = DesignServerPrioritiesBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = ServerPriorityAdapter(context, entries.toMutableList())

    override val root: View
        get() = binding.root

    /** Current order, top-first. Index 0 is the highest priority. */
    val entries: List<ServerPriorityEntry>
        get() = adapter.entries

    suspend fun replaceAll(newEntries: List<ServerPriorityEntry>) {
        withContext(Dispatchers.Main) {
            adapter.replaceAll(newEntries)
        }
    }

    fun requestReset() {
        requests.trySend(Request.Reset)
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        /**
         * A drag is started by holding the row anywhere, which is what the
         * screen tells the user to do. The handle on the right is there to
         * advertise that the row is draggable, not as the only grip — a
         * grip-only row is easy to miss and hard to hit.
         */
        override fun isLongPressDragEnabled(): Boolean = true

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            return makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0,
            )
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val moved = adapter.move(
                viewHolder.bindingAdapterPosition,
                target.bindingAdapterPosition,
            )

            if (moved) {
                reordered = true
            }

            return moved
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(
            viewHolder: RecyclerView.ViewHolder?,
            actionState: Int,
        ) {
            super.onSelectedChanged(viewHolder, actionState)

            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.isPressed = true
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)

            viewHolder.itemView.isPressed = false

            // A drag that ended where it started still lands here, and so
            // does a long-press the user aborted. Report only when a row
            // actually changed rank, so a stray press cannot trigger a
            // save and a config reload.
            if (reordered) {
                reordered = false

                // Posted: RecyclerView is still finishing the drop
                // animation here, and notifying inside clearView throws
                // "Cannot call this method while RecyclerView is
                // computing a layout or scrolling".
                recyclerView.post { adapter.refreshRanks() }

                requests.trySend(Request.OrderChanged)
            }
        }

        private var reordered = false
    })

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.bindAppBarElevation(binding.activityBarLayout)
        binding.mainList.recyclerList.applyLinearAdapter(context, adapter)

        touchHelper.attachToRecyclerView(binding.mainList.recyclerList)
    }
}
