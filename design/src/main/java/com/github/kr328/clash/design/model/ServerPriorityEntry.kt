package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.component.FleetBadge

/**
 * One row of the server-priorities screen.
 *
 * There is deliberately no priority field: the position in the list *is*
 * the priority (first = tried first). Storing a number alongside the
 * order would give two sources of truth that could disagree after a
 * drag; the number shown in the row is derived from the index at bind
 * time instead.
 *
 * The badges are a snapshot taken when the screen was opened. Fleet
 * verdicts only change on the hourly sweep, and the list is rebuilt when
 * the service restarts, so they cannot go stale within a visit in a way
 * that would mislead a reordering decision.
 */
data class ServerPriorityEntry(
    val name: String,
    val gemini: FleetBadge = FleetBadge.None,
    val youtube: FleetBadge = FleetBadge.None,
)
