# Review of Fleet Status (feat/fleet-status)

## 1. Concurrency and correctness in `fleet.go`
No issues found at any severity level.
- The `atomic.Int64` handling and mutex locking around `loadMu` successfully eliminate the startup race without unnecessary blocking.
- Deferring `lastAttempt.Store` ensures it always executes, and due to LIFO defer execution ordering, it occurs exactly while `loadMu` is still held, safely signaling to queued waiters that the load completed.
- Concurrent reads of a partially written JSON file via `os.ReadFile` simply yield a JSON parse error, safely aborting the snapshot update and preserving the old data until the next throttled check.

## 2. The Kotlin fetcher (`FleetStatus.kt`)
No issues found at any severity level.
- The bounded read safely terminates if a hijacked or captive portal URL returns an infinite stream.
- Accumulating bytes in `ByteArrayOutputStream` avoids chunk-straddling UTF-8 decode corruption.
- Process-specific `.tmp` files (`android.os.Process.myPid()`) correctly avoid interleaving writes between the UI and service processes, and `renameTo` provides robust atomic replacement on Android file systems.
- The `minAgeMillis` check handles clock skews effectively (`age in 0 until minAgeMillis`).

## 3. Name matching in `fleet.go`
No issues found at any severity level.
- `keysOf` accurately builds combinations of stripped suffixes and unstripped bases, passing them all into the mapping index.
- Ambiguous keys are explicitly deleted from the index mapping, which safely prevents misattribution (falling back to "no verdict" instead of an incorrect bucket).
- `stripTransportSuffix` correctly trims known transport tags.

## 4. Scheduling
No issues found at any severity level.
- `millisUntilNextSlot` correctly calculates the time until the next UTC `:35` mark, handling clock wrap-around accurately.
- Using simple coroutine delays over `SCHEDULE_EXACT_ALARM` respects Android 12+ strict foreground service constraints. The `minAgeMillis` caching handles missed scheduled events that trigger on ad-hoc UI events.

## 5. UI
No issues found at any severity level.
- The `ProxyView` layout cleanly adapts dynamically, preserving vertical centering for items without fleet data, but accurately shifting delay times up for multi-column configurations with badges.
- Badges are correctly positioned right-aligned under the delay text, preventing overlap or truncation.
- Paint paths and canvas states are safely managed (`reset()` is appropriately utilized).

## 6. Secret hygiene
No issues found at any severity level.
- The URL is explicitly drawn from the `local.properties` build configuration securely, reverting to empty rather than exposing the URL.
- Only the `responseCode` and broad exceptions are logged in `FleetStatus.kt`, avoiding leaking query parameters or full pathing into Logcat.

## Final Verdict
APPROVE
