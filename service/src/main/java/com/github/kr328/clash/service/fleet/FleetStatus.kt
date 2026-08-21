package com.github.kr328.clash.service.fleet

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.BuildConfig
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.clashDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the fleet-status feed — an hourly, server-side sweep that
 * reports per node whether Gemini answers and which country YouTube
 * attributes to the exit IP — and drops it into the native core's home
 * directory as `fleet_status.json`.
 *
 * Kotlin only fetches and validates. All interpretation (name matching,
 * freshness, ru/foreign classification) happens once in Go, in
 * `cfa/native/fleet`, which notices the rewritten file on its own — so
 * neither a JNI call nor an AIDL round trip is needed here, and either
 * process may refresh.
 *
 * See `docs/fleet_status.md` in the outer vpn-leak-testing repo.
 */
object FleetStatus {
    /**
     * Outcome of a refresh attempt. "Nothing was written" is not one
     * state but two: a deliberate skip (the cache is young enough, or no
     * URL is configured) must not be retried, while a failed fetch
     * should be.
     */
    enum class Result { Updated, Skipped, Failed }

    const val FILE_NAME = "fleet_status.json"

    /**
     * The producer runs at :30 of every hour and takes about two
     * minutes; :35 leaves margin without waiting out a whole hour.
     * Anchored to UTC (epoch arithmetic), like the producer's schedule.
     */
    const val REFRESH_MINUTE_OF_HOUR = 35

    /**
     * Payload shapes this build accepts, mirroring `supportedSchemas` in
     * `cfa/native/fleet`. Schema 2 dropped the "error" Gemini value — a
     * failed check no longer overwrites the last real verdict — and
     * added the `gemini_*` freshness fields; schema 1 still parses, so a
     * rolled-back producer does not take the feature down with it.
     *
     * Keep this in step with the Go side: accepting a payload here that
     * Go then refuses would replace good verdicts with a file nothing
     * reads.
     */
    private val SUPPORTED_SCHEMAS = setOf(1, 2)
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** A sane feed is a few KiB; anything past this is not our JSON. */
    private const val MAX_BODY_BYTES = 1 shl 20

    /**
     * Feed URL: the per-device override wins, otherwise the value baked
     * in from `local.properties` at build time. Empty means the feature
     * is simply not configured — every entry point then no-ops quietly.
     */
    fun urlOf(context: Context): String {
        val override = ServiceStore(context).fleetStatusUrl.trim()

        return override.ifEmpty { BuildConfig.FLEET_STATUS_URL.trim() }
    }

    fun fileOf(context: Context): File = File(context.clashDir, FILE_NAME)

    /**
     * Fetches the feed and replaces the sidecar.
     *
     * @param minAgeMillis skip the download when the cached file is
     * younger than this. Lets "refresh on every app start" and "refresh
     * on every delay test" be called freely without hammering the host.
     */
    suspend fun refresh(context: Context, minAgeMillis: Long = 0): Result =
        withContext(Dispatchers.IO) {
            val url = urlOf(context)
            if (url.isEmpty()) {
                Log.d("Fleet: no feed url configured, skipping refresh")

                return@withContext Result.Skipped
            }

            val file = fileOf(context)
            if (minAgeMillis > 0 && file.isFile) {
                val age = System.currentTimeMillis() - file.lastModified()
                if (age in 0 until minAgeMillis) {
                    Log.d("Fleet: cached feed is ${age}ms old, skipping refresh")

                    return@withContext Result.Skipped
                }
            }

            val body = download(url) ?: return@withContext Result.Failed

            if (!isPlausibleFeed(body)) {
                // A captcha page, an error JSON or a truncated body must
                // never replace verdicts that are still good.
                Log.w("Fleet: rejected feed payload (${body.length} chars), keeping previous")

                return@withContext Result.Failed
            }

            if (!write(context, file, body)) {
                return@withContext Result.Failed
            }

            Log.i("Fleet: feed updated (${body.length} chars)")

            Result.Updated
        }

    private fun download(url: String): String? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }

            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w("Fleet: feed responded $code")

                    return null
                }

                // Bounded read: a hijacked or misconfigured host must not
                // be able to stream the process out of memory. Bytes are
                // accumulated and decoded once — node names are Cyrillic,
                // and decoding per chunk would corrupt any multi-byte
                // sequence that straddles a buffer boundary.
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(8 * 1024)
                    val out = ByteArrayOutputStream()

                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break

                        if (out.size() + read > MAX_BODY_BYTES) {
                            Log.w("Fleet: feed exceeds $MAX_BODY_BYTES bytes, aborting")

                            return null
                        }

                        out.write(buffer, 0, read)
                    }

                    out.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            // Offline, DNS failure, TLS error, host down — all expected
            // and all non-fatal: the previous sidecar stays in place.
            Log.w("Fleet: feed download failed: ${it.message}", it)

            null
        }
    }

    /**
     * Cheap sanity check before overwriting good data: the payload must
     * parse, carry a schema this build understands, and describe at
     * least one node.
     */
    private fun isPlausibleFeed(body: String): Boolean {
        return runCatching {
            val root = JSONObject(body)

            root.optInt("schema") in SUPPORTED_SCHEMAS && root.optJSONObject("nodes")?.length()
                ?.let { it > 0 } == true
        }.getOrDefault(false)
    }

    /**
     * Atomic replace. The temp name carries the pid because the UI and
     * the service process can both refresh; a shared fixed ".tmp" path
     * would let two writers interleave and rename half a payload over
     * the live file.
     */
    private fun write(context: Context, file: File, body: String): Boolean {
        return runCatching {
            val dir = context.clashDir
            dir.mkdirs()

            val tmp = File(dir, "$FILE_NAME.${android.os.Process.myPid()}.tmp")

            try {
                tmp.writeText(body)

                if (!tmp.renameTo(file)) {
                    // Same-directory rename should not fail; fall back to
                    // a direct write rather than dropping the update.
                    file.writeText(body)
                }
            } finally {
                tmp.delete()
            }

            true
        }.getOrElse {
            Log.w("Fleet: writing $FILE_NAME failed: ${it.message}", it)

            false
        }
    }

    /**
     * Milliseconds until the next :35 slot, computed against the epoch so
     * it lands on :35 UTC regardless of the device's timezone — matching
     * the producer, which runs on UTC wall-clock hours.
     */
    fun millisUntilNextSlot(now: Long = System.currentTimeMillis()): Long {
        val hour = 60L * 60 * 1000
        val target = REFRESH_MINUTE_OF_HOUR * 60L * 1000
        val intoHour = ((now % hour) + hour) % hour

        return if (intoHour < target) target - intoHour else hour - intoHour + target
    }
}
