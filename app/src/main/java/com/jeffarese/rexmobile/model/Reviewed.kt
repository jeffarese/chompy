package com.jeffarese.rexmobile.model

import android.content.SharedPreferences
import com.jeffarese.rexmobile.rex.ProgramStatus

/**
 * When the person last looked at each block's finished or failed agent: the updated_at of the newest done or
 * error report they had seen. OSC 7501's done and error wait for review, so an agent that finished while the
 * phone was away, asleep or not yet started still shows as new. Without [prefs] it is kept in memory only.
 * Block IDs are never reused, so an entry goes when its block does.
 */
class Reviewed(private val prefs: SharedPreferences? = null, private val hostId: String = "") {
    private val memory = mutableMapOf<String, String>()
    private val prefix get() = "$hostId/"

    fun get(bid: String): String? = prefs?.getString(prefix + bid, null) ?: memory[bid]

    /** Note the block's reports as seen. */
    fun seen(bid: String, statuses: List<ProgramStatus>) {
        val at = latestResult(statuses) ?: return
        if (get(bid)?.let { it >= at } == true) return
        memory[bid] = at
        prefs?.edit()?.putString(prefix + bid, at)?.apply()
    }

    /** True when the block has a done or error report newer than the last one seen. */
    fun hasNew(bid: String, statuses: List<ProgramStatus>): Boolean {
        val at = latestResult(statuses) ?: return false
        return get(bid)?.let { at > it } ?: true
    }

    fun forget(bids: Collection<String>) {
        memory.keys.removeAll(bids.toSet())
        prefs?.edit()?.apply { bids.forEach { remove(prefix + it) } }?.apply()
    }

    /** Forget every block of this host but [present]. */
    fun retain(present: Set<String>) {
        memory.keys.retainAll(present)
        val p = prefs ?: return
        val stale = p.all.keys.filter { it.startsWith(prefix) && it.removePrefix(prefix) !in present }
        if (stale.isNotEmpty()) p.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    /** The newest done or error report of the agent itself, not of its subtasks. RFC 3339 times from one server compare as strings. */
    private fun latestResult(statuses: List<ProgramStatus>): String? =
        statuses.filter { (it.state == "done" || it.state == "error") && isAgentRecord(it, statuses) }
            .maxOfOrNull { it.updatedAt }?.takeIf { it.isNotEmpty() }
}

/**
 * Whether [rec] speaks for the agent rather than one of its subtasks: the root record (no id) when the block
 * has one, else a top-level one. With OSC 7501, a program that reports a root record and records such as
 * build and build/test is describing its subtasks with those; alerts and the board's news go by the agent.
 */
fun isAgentRecord(rec: ProgramStatus, all: List<ProgramStatus>): Boolean = when {
    rec.id == null -> true
    rec.id.contains('/') -> false
    else -> all.none { it.id == null }
}
