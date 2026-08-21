package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        // Keep ON (upstream default). Initially tried OFF to present a
        // single 0.0.0.0/0 default route (less detector-suspicious),
        // but Android VpnService then captures loopback too — breaking
        // adb-forwarded local ports (Maestro driver on 127.0.0.1:7001,
        // app dev servers, anything bound to localhost). The detector
        // flags "dedicated routes to tun0" and "routing indicates split
        // tunneling" are inherent to Android's per-UID VPN routing and
        // cannot be closed without root/magisk. Accept the flag; keep
        // the device functional.
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        // Default OFF: attaching an HTTP proxy to the VpnService surfaces
        // via System.getProperty("http.proxyHost") and is immediately
        // flagged by both RKNHardering and YourVPNDead as a VPN marker.
        defaultValue = false
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "system"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )

    /**
     * Overrides the build-time fleet-status feed URL
     * (`BuildConfig.FLEET_STATUS_URL`, injected from `local.properties`
     * so the secret path never lands in git). Empty = use the built-in
     * one. Lives here rather than in UiStore because the fetcher also
     * runs in the service process.
     */
    var fleetStatusUrl by store.string(
        key = "fleet_status_url",
        defaultValue = ""
    )
}