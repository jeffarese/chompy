package com.jeffarese.rexmobile.model

import android.content.Context
import com.jeffarese.rexmobile.BuildConfig
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.rex.RexJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

@Serializable
data class HostConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** The server's base URL, such as https://studio-rex.tail1234.ts.net. */
    val url: String,
    val icon: String? = null,
)

/** The hosts this phone connects to, kept in shared preferences. */
class HostStore(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(HostConfig.serializer())
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<HostConfig>> = _hosts

    private fun load(): List<HostConfig> {
        val raw = prefs.getString("list", null)
        if (raw != null) return runCatching { RexJson.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
        // Runs while _hosts is being created, so it writes the preference only.
        return defaults().also { persist(it) }
    }

    private fun persist(list: List<HostConfig>) {
        prefs.edit().putString("list", RexJson.encodeToString(serializer, list)).apply()
    }

    private fun save(list: List<HostConfig>) {
        persist(list)
        _hosts.value = list
    }

    fun add(label: String, address: String, icon: String? = null): HostConfig {
        val url = ControlConnection.normalizeHostUrl(address)
        // Also a host kept at the address it answered on instead, such as its :7297.
        val existing = _hosts.value.firstOrNull { h -> ControlConnection.urlsToTry(url).any { h.url.equals(it, ignoreCase = true) } }
        if (existing != null) return existing
        val host = HostConfig(label = label.ifBlank { hostLabelFrom(url) }, url = url, icon = icon)
        save(_hosts.value + host)
        return host
    }

    fun update(host: HostConfig) = save(_hosts.value.map { if (it.id == host.id) host else it })

    fun remove(id: String) = save(_hosts.value.filterNot { it.id == id })

    fun move(id: String, delta: Int) {
        val list = _hosts.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        save(list)
    }

    private fun defaults(): List<HostConfig> =
        BuildConfig.DEFAULT_HOSTS.split(',').mapNotNull { entry ->
            val (label, address) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { HostConfig(label = label.trim(), url = ControlConnection.normalizeHostUrl(address)) }.getOrNull()
        }

    companion object {
        /** "studio-rex.tail1234.ts.net" → "studio-rex". */
        fun hostLabelFrom(url: String): String =
            url.substringAfter("://").substringBefore('.').substringBefore(':')
    }
}
