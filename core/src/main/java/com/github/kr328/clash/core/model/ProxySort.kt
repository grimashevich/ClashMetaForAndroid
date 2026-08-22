package com.github.kr328.clash.core.model

enum class ProxySort {
    Default, Title, Delay,

    /**
     * Fleet status: exits that behave as foreign first, Russian-looking
     * exits next, unverdicted last; ties broken by delay. Parsed by name
     * in native/tunnel.go, so this spelling is part of the contract.
     */
    Fleet
}
