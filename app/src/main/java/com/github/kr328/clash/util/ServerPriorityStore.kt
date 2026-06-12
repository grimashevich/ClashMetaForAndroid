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

    fun savePriorities(context: Context, priorities: Map<String, Int>) {
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
    }
}
