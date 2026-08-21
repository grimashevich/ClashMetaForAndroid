package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.fleet.FleetStatus
import kotlinx.coroutines.delay

/**
 * Keeps the fleet-status sidecar fresh for as long as the core runs.
 *
 * The feed is produced at :30 of every hour, so this refreshes at :35
 * (UTC-anchored, same as the producer). A plain coroutine `delay` is
 * enough — the module only lives while the service does, and the service
 * is a foreground service — which avoids `SCHEDULE_EXACT_ALARM`, a
 * permission that would be both intrusive to request and pointless for
 * data with an hour of slack.
 *
 * The UI refreshes on its own (app start, delay test), and Go re-reads
 * the file whenever it changes, so a missed tick here is a cosmetic
 * delay, never a correctness problem.
 */
class FleetStatusModule(service: Service) : Module<Unit>(service) {
    private companion object {
        /**
         * On service start the cache may be minutes or days old; skip the
         * download only if something else refreshed it just now.
         */
        const val START_MIN_AGE = 5 * 60 * 1000L

        /** A failed slot is retried a few times before waiting an hour. */
        const val RETRY_DELAY = 5 * 60 * 1000L
        const val RETRY_LIMIT = 3
    }

    override suspend fun run() {
        refreshWithRetry(START_MIN_AGE)

        while (true) {
            val wait = FleetStatus.millisUntilNextSlot()

            Log.d("Fleet: next refresh in ${wait / 1000}s")

            delay(wait)

            refreshWithRetry()
        }
    }

    private suspend fun refreshWithRetry(minAgeMillis: Long = 0) {
        repeat(RETRY_LIMIT) { attempt ->
            // Only a genuine failure (offline at the moment of the slot)
            // is retried; a deliberate skip must not stall the loop, or
            // a young cache at startup would cost us the next slot.
            if (FleetStatus.refresh(service, minAgeMillis) != FleetStatus.Result.Failed) return

            if (attempt < RETRY_LIMIT - 1) {
                delay(RETRY_DELAY)
            }
        }

        Log.w("Fleet: giving up on this slot after $RETRY_LIMIT attempts")
    }
}
