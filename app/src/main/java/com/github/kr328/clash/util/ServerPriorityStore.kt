package com.github.kr328.clash.util

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Reads/writes the server-priority files shared with the Go config
 * layer (core/src/main/golang/native/config/customgroups.go):
 *
 * - known_servers.json — written by Go on every profile load/import,
 *   lists server names in subscription order;
 * - priorities.json — written here, read by Go when building the
 *   "⚡ Приоритет" fallback group.
 *
 * Both live in [Context.clashDir], which is the same directory the
 * native side resolves via constant.Path (Bridge.nativeInit gets
 * filesDir/clash as home). Writes are atomic (temp + rename) because
 * the Go side may re-read during a concurrent profile reload.
 */
object ServerPriorityStore {
    private const val KNOWN_SERVERS_FILE = "known_servers.json"
    private const val PRIORITIES_FILE = "priorities.json"

    fun loadKnownServers(context: Context): List<String> {
        val file = File(context.clashDir, KNOWN_SERVERS_FILE)
        if (!file.isFile) return emptyList()

        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.getJSONArray("servers")
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun loadPriorities(context: Context): Map<String, Int> {
        val file = File(context.clashDir, PRIORITIES_FILE)
        if (!file.isFile) return emptyMap()

        return runCatching {
            val root = JSONObject(file.readText())
            val priorities = root.getJSONObject("priorities")
            priorities.keys().asSequence().associateWith { priorities.getInt(it) }
        }.getOrDefault(emptyMap())
    }

    /**
     * Orders servers the way the Go side will, so the screen shows the
     * order that is actually in force rather than a UI-side guess.
     *
     * Mirrors orderByPriority in
     * core/src/main/golang/native/config/customgroups.go: assigned
     * priorities ascending first, then everything unassigned in
     * subscription order. **Keep the two in step.**
     *
     * Since the drag UI assigns every server a rank, the unassigned
     * branch only matters for a priorities.json written by the older
     * numeric-entry screen — [saveOrder] normalises that away the first
     * time the user reorders.
     */
    fun orderServers(servers: List<String>, priorities: Map<String, Int>): List<String> {
        return servers.withIndex().sortedWith(
            compareBy(
                { (_, name) -> if (priorities.containsKey(name)) 0 else 1 },
                { (_, name) -> priorities[name] ?: 0 },
                { (index, _) -> index },
            )
        ).map { (_, name) -> name }
    }

    /**
     * Persists an explicit top-to-bottom order as ranks 1..N.
     *
     * Ranks are dense and start at 1 so the numbers the user sees in the
     * list are the numbers on disk; the Go side only compares them, so
     * any increasing sequence would do, but a gap-free one keeps the file
     * readable when debugging.
     */
    fun saveOrder(context: Context, orderedNames: List<String>): Boolean {
        return savePriorities(
            context,
            orderedNames.withIndex().associate { (index, name) -> name to index + 1 },
        )
    }

    /**
     * Persists the priority map. Returns true on success. Wrapped in
     * runCatching so a write failure (out of storage, permissions) degrades
     * gracefully instead of throwing out of the caller's IO coroutine and
     * crashing the activity.
     */
    fun savePriorities(context: Context, priorities: Map<String, Int>): Boolean {
        return runCatching {
            val root = JSONObject()
                .put("version", 1)
                .put("priorities", JSONObject(priorities.toMap()))

            val file = File(context.clashDir, PRIORITIES_FILE)
            val tmp = File(context.clashDir, "$PRIORITIES_FILE.tmp")

            context.clashDir.mkdirs()
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                // renameTo across the same directory shouldn't fail; fall back
                // to a direct write rather than silently dropping the change
                file.writeText(root.toString())
                tmp.delete()
            }
            true
        }.getOrElse { false }
    }
}
