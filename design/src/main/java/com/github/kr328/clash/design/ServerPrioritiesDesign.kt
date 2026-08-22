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

    /**
     * Applies fleet verdicts onto the rows already on screen, keyed by
     * server name.
     *
     * Two things this must not do, both learned from review round 1:
     * it must not replace the list (that is `notifyDataSetChanged`, which
     * desyncs an in-flight drag), and it must not touch the rows while a
     * drag is running at all — rebinding the row under the finger visibly
     * resets it. So the update is applied in place, leaving order and
     * size untouched, and is held back until the drag settles.
     */
    suspend fun applyBadges(byName: Map<String, ServerPriorityEntry>) {
        withContext(Dispatchers.Main) {
            if (dragging) {
                pendingBadges = byName
            } else {
                adapter.applyBadges(byName)
            }
        }
    }

    fun requestReset() {
        requests.trySend(Request.Reset)
    }

    /** True between a drag starting and its drop animation being cleared. */
    private var dragging = false

    /** Set when badges arrived mid-drag; applied once the drag settles. */
    private var pendingBadges: Map<String, ServerPriorityEntry>? = null

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
            return adapter.move(
                viewHolder.bindingAdapterPosition,
                target.bindingAdapterPosition,
            )
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(
            viewHolder: RecyclerView.ViewHolder?,
            actionState: Int,
        ) {
            super.onSelectedChanged(viewHolder, actionState)

            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                dragStartIndex = viewHolder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                viewHolder?.itemView?.isPressed = true
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)

            viewHolder.itemView.isPressed = false
            dragging = false

            // Compare where the row started with where it ended, rather
            // than trusting "onMove fired at least once": dragging a row
            // down and back up before letting go fires onMove twice and
            // lands on the original order. Saving that would rewrite
            // priorities.json and trigger a config reload for nothing.
            val landed = viewHolder.bindingAdapterPosition
            val moved = dragStartIndex != RecyclerView.NO_POSITION &&
                    landed != RecyclerView.NO_POSITION &&
                    landed != dragStartIndex

            dragStartIndex = RecyclerView.NO_POSITION

            // Posted: RecyclerView is still finishing the drop animation
            // here, and notifying inside clearView throws "Cannot call
            // this method while RecyclerView is computing a layout or
            // scrolling".
            recyclerView.post {
                if (moved) {
                    adapter.refreshRanks()
                }

                pendingBadges?.let {
                    pendingBadges = null

                    adapter.applyBadges(it)
                }
            }

            if (moved) {
                requests.trySend(Request.OrderChanged)
            }
        }

        private var dragStartIndex = RecyclerView.NO_POSITION
    })

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.bindAppBarElevation(binding.activityBarLayout)
        binding.mainList.recyclerList.applyLinearAdapter(context, adapter)

        touchHelper.attachToRecyclerView(binding.mainList.recyclerList)
    }
}
