# Code Review: Custom Balancers

## CRITICAL

1. **Global State Overwrite During Background Fetch Validation**
   - **File:** `core/src/main/golang/native/config/customgroups.go` (Lines 76-80) and `process.go` (Line 28)
   - **Issue:** `patchCustomGroups` runs for *every* profile processed by `UnmarshalAndPatch`, which is invoked not only during active profile `Load()` but also during background `FetchAndValid()`. When a background update occurs for a non-active profile, `writeKnownServers()` will overwrite the global `known_servers.json` with the inactive profile's servers. The UI will then incorrectly display the inactive profile's servers while the user is still connected to their active profile.
   - **Suggested Fix:** Do not write the sidecar file during fetch-validation. You can pass a `isValidation` flag to the processor chain, avoid writing it inside the processor (doing it directly in `Load()`), or write `known_servers.json` to the specific `profileDir` instead of resolving it globally via `constant.Path.Resolve`.

## MAJOR

1. **Race Condition on Hardcoded Temporary File**
   - **File:** `core/src/main/golang/native/config/customgroups.go` (Line 73)
   - **Issue:** `writeKnownServers` uses a hardcoded temporary file (`knownServersPath() + ".tmp"`). Since this logic can be executed concurrently (e.g., a background update overlapping with an active profile reload), two goroutines could write to the same `.tmp` file simultaneously. `os.WriteFile` truncates but is not atomic internally across multiple processes/threads, potentially causing a corrupted JSON payload to be renamed over the active file.
   - **Suggested Fix:** Append a random suffix, a timestamp, or use `os.CreateTemp` to ensure each concurrent write creates a unique temporary file before calling `os.Rename`.

## MINOR

1. **Missing Exception Handling for File Writes**
   - **File:** `app/src/main/java/com/github/kr328/clash/util/ServerPriorityStore.kt` (Lines 56 & 60)
   - **Issue:** `tmp.writeText(root.toString())` and `file.writeText(root.toString())` can throw an `IOException` (e.g., if the device is out of storage or permissions fail). This function is called within a `withContext(Dispatchers.IO)` block in `ServerPrioritiesActivity.kt`, but without a `try-catch` block. Unhandled exceptions will crash the activity coroutine.
   - **Suggested Fix:** Wrap the file writing operations in a `try-catch` block or use `runCatching` to degrade gracefully on write failures.

2. **Unhandled Service Lifecycle Events in UI**
   - **File:** `app/src/main/java/com/github/kr328/clash/ServerPrioritiesActivity.kt` (Lines 34-35)
   - **Issue:** The `events.onReceive` block ignores all global UI events (like `Event.ClashStart`, `Event.ClashStop`, or `Event.ServiceRecreated`). Other Settings screens explicitly handle these to `recreate()` the activity or update state.
   - **Suggested Fix:** Mimic the standard lifecycle handling (e.g., `Event.ServiceRecreated -> recreate()`) to ensure the UI stays synchronized with the service state.

3. **Silent Truncation of MATCH Options (Edge Case)**
   - **File:** `core/src/main/golang/native/config/customgroups.go` (Line 144)
   - **Issue:** Although rare since `MATCH` usually accepts just the target proxy, if a user profile defines a `MATCH` rule with trailing options (e.g., `MATCH,DIRECT,no-resolve`), `matchRuleTarget` correctly isolates `"DIRECT,no-resolve"` as the target name. However, the rewrite `newMatchRule := "MATCH," + customGroupStrategy` effectively drops those trailing options.
   - **Suggested Fix:** For absolute correctness, `matchRuleTarget` could return the full suffix options to append them back, or you can consider this acceptable since standard Clash profiles almost exclusively use `MATCH,<target>` with no extras.

*(Note: All other requested focus areas—correctness of priority ordering, group-name collision safety, UI JSON read parsing edge cases, AndroidManifest wiring, input dialog validator, and atomic rename fallback—are implemented correctly and clearly clean.)*
