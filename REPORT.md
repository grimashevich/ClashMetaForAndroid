# Merge Resolution Review

The merge resolution at `HEAD` successfully incorporates upstream changes into the fork while preserving the custom modifications.

## Findings

APPROVE, nothing found.

### Anti-detect TUN configuration

The anti-detect TUN configuration in `service/src/main/java/com/github/kr328/clash/service/NetworkBridgeService.kt` survived the merge perfectly.
- The session name correctly remains `"Network Bridge"`.
- The public DNS servers are retained correctly (`1.1.1.1` and `2606:4700:4700::1111`) with their corresponding explanatory comment.
- The `FleetStatusModule` installation is intact (`install(FleetStatusModule(self))`).

### Fleet-status URL injection

The fleet-status URL injection is present in all three workflows (`.github/workflows/build-pre-release.yaml`, `.github/workflows/build-release.yaml`, `.github/workflows/build-debug.yaml`).
The secret logic correctly reads `secrets.FLEET_STATUS_URL` and appends it to `local.properties`.

### Custom group injection and fleet reader

The submodules for `core/src/foss/golang/clash` are correctly pointing to `cmfa-main` and pinned to `8cfb6643`. The go source files in `core/src/main/golang/native/config/` and `core/src/main/golang/native/fleet/` remain intact and reflect the custom injection and fleet reader capabilities.

### ServiceStore/settings entries

`service/src/main/java/com/github/kr328/clash/service/store/ServiceStore.kt` contains the necessary settings for the fleet feature (such as `fleetStatusUrl`).

### Deliberate resolution decisions

1. **build.gradle.kts**: Updating to `2.11.33` (versionCode `211033`) is correct. This is monotonic compared to the previous custom build (`2.11.32` / `211032`) and allows in-place updates.
2. **Submodule pin**: The pin to `8cfb6643` with branch `cmfa-main` in the `.gitmodules` file is a sensible choice and works well.
3. **INTERACT_ACROSS_USERS**: Keeping `android.permission.INTERACT_ACROSS_USERS` is acceptable. Since it requires ADB to grant and detector apps aren't reading the permission list, this shouldn't compromise the anti-detect requirements.
4. **Stable core branch**: Switching to `cmfa-main` in `.gitmodules` avoids branch rot and maintains stability.

### Secret hygiene

A quick review of secrets ensures that no actual URL, exit IP, or the `FLEET_STATUS_URL` have been committed in plain text. Everything relies correctly on GitHub Secrets (`${{ secrets.FLEET_STATUS_URL }}`).

## Verdict

APPROVE
