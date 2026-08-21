package com.github.kr328.clash.service.util

import android.content.Context
import java.io.File

val Context.importedDir: File
    get() = filesDir.resolve("imported")

val Context.pendingDir: File
    get() = filesDir.resolve("pending")

val Context.processingDir: File
    get() = filesDir.resolve("processing")

val File.directoryLastModified: Long?
    get() {
        return walk().map { it.lastModified() }.maxOrNull()
    }

/**
 * The native core's home directory (`constant.Path`): the sidecar files
 * Kotlin and Go exchange — `priorities.json`, `known_servers.json`,
 * `fleet_status.json` — all live here. Mirrors `Context.clashDir` in the
 * app module; both processes resolve to the same path because they share
 * one `filesDir`.
 */
val Context.clashDir: File
    get() = filesDir.resolve("clash")
