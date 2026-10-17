package com.robert4lyf.hypershop.core.model

/**
 * The covered/DONE rules of ADR 002, over the contributions of one item.
 * Pure: the repository (`:data`) and the merge engine both call these.
 */
data class ContributionState(
    val id: Long,
    val useId: Long,
    val qty: QtyBase,
    val createdListRev: Long,
    val coveredBy: Long? = null,
)

object Coverage {
    /** DONE: at least one contribution, and every one covered by a tick. */
    fun isDone(contributions: List<ContributionState>): Boolean = contributions.isNotEmpty() && contributions.all { it.coveredBy != null }

    /** Partly ticked: some covered, some not. */
    fun isPartial(contributions: List<ContributionState>): Boolean =
        contributions.any { it.coveredBy != null } && contributions.any { it.coveredBy == null }

    fun total(contributions: List<ContributionState>): QtyBase = contributions.fold(QtyBase.PRESENCE) { acc, c -> acc + c.qty }

    /** What is still to buy: the sum of the uncovered contributions. */
    fun uncovered(contributions: List<ContributionState>): QtyBase = total(contributions.filter { it.coveredBy == null })

    fun covered(contributions: List<ContributionState>): QtyBase = total(contributions.filter { it.coveredBy != null })
}
