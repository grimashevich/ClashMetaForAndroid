# Server Priorities and Custom Groups Review

## 1. ServerPriorityStore.orderServers vs orderByPriority
**Verdict:** APPROVE, nothing found.
Both implementations sort the lists identically: assigned servers are ordered by their priority value (ascending) followed by their original subscription index. Unassigned servers follow, ordered by their original subscription index. The logic perfectly handles ties, missing servers (ignored), negative priorities, and mixed subsets exactly the same across both languages.

## 2. Drag correctness
**Verdict:** APPROVE, nothing found.
- `bindingAdapterPosition` returning `NO_POSITION` (-1) is safely caught by the bounds checks in `adapter.move()`.
- Desync issues are prevented by wrapping the `refreshRanks` call in `recyclerView.post`, safely delaying it until after layout computation.
- The `Collections.swap` loop matches a remove/insert perfectly for both upward and downward drag directions.

## 3. Persistence
**Verdict:** MAJOR
**File:** `design/src/main/java/com/github/kr328/clash/design/ServerPrioritiesDesign.kt` (lines 86-90, 110-120)
**Issue:** If a user drags a row but drops it at the exact original position (e.g. dragging down and back up before releasing), `ItemTouchHelper.Callback.onMove` still fires and sets `reordered = true`. Once the drag ends, `clearView` consumes the flag and fires `Request.OrderChanged`, unnecessarily persisting an unchanged order and triggering a backend config reload.
**Suggested Fix:** Rather than relying on `onMove` calls, track the initial position in `onSelectedChanged` and compare it against `viewHolder.bindingAdapterPosition` inside `clearView` to accurately determine if the rank truly changed.

## 4. The badge mapping
**Verdict:** MINOR
**File:** `app/src/main/java/com/github/kr328/clash/ServerPrioritiesActivity.kt` (line 140)
**Issue:** The mapping logic misses the `!proxy.isGroup` condition present in `ProxyViewState`. While the "⚡ Приоритет" group injected by `customgroups.go` typically only contains plain proxies, if a user's subscription natively defines a group with this exact name, its members might include other groups. `applyBadges` would incorrectly apply badges to them while the proxy list view correctly omits them.
**Suggested Fix:** Update the condition to explicitly ignore groups, matching `ProxyViewState` exactly: `val hasVerdict = !proxy.isGroup && proxy.fleetReachable && proxy.fleetCheckedAt > 0`.

## 5. FleetBadges extraction
**Verdict:** APPROVE, nothing found.
The extraction flawlessly preserves the exact shapes, layout, and alpha channel muting for Russian exits. The `Paint` and `Path` objects are passed directly from the callers (`ProxyView` and `FleetBadgeView`), which own their respective instances, making the shared badge rendering code 100% thread-safe and free from concurrency or state-sharing issues.

## 6. customgroups.go
**Verdict:** APPROVE, nothing found.
The collision-detection slice explicitly includes all five injected groups (including `customGroupGemini`). Furthermore, nothing in the logic hardcodes group indices, ensuring safety regardless of how many groups are injected.

## 7. Lifecycle
**Verdict:** CRITICAL
**File:** `app/src/main/java/com/github/kr328/clash/ServerPrioritiesActivity.kt` (line 149)
**Issue:** `applyBadges` suspends the coroutine while fetching verdicts from the backend. Because the screen paints before this (using the order from disk), the UI is responsive and a user can immediately initiate a drag. If `applyBadges` resumes while a drag is in-flight, it calls `design.replaceAll(updated)`, invoking `notifyDataSetChanged()`. This replaces the active `RecyclerView` bindings mid-drag, causing the `ItemTouchHelper` to desync and severely breaking the UI state.
**Suggested Fix:** Instead of wiping the list with a global `notifyDataSetChanged()`, update the badges dynamically via property mutation followed by `notifyItemRangeChanged(0, itemCount)`, or implement a mechanism to block data-set resets while a drag gesture is actively being handled.

## Final Verdict
REQUEST CHANGES