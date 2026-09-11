package com.sigeye.experiments.watchlist

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persisted watch rules, plus the live in-memory record of what has fired. */
class WatchStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("watchlist", Context.MODE_PRIVATE)

    private val _rules = MutableStateFlow(load())
    val rules: StateFlow<List<WatchRule>> = _rules

    private val _hits = MutableStateFlow<List<WatchHit>>(emptyList())

    /** Most recent first, capped - this is a log to glance at, not an archive. */
    val hits: StateFlow<List<WatchHit>> = _hits

    /** Whether the background watch is armed. Survives a restart. */
    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_ARMED, value).apply()

    fun upsert(rule: WatchRule) {
        val existing = _rules.value.indexOfFirst { it.id == rule.id }
        _rules.value = if (existing >= 0) {
            _rules.value.toMutableList().also { it[existing] = rule }
        } else {
            _rules.value + rule
        }
        save()
    }

    fun delete(id: String) {
        _rules.value = _rules.value.filterNot { it.id == id }
        save()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _rules.value.firstOrNull { it.id == id }?.let { upsert(it.copy(enabled = enabled)) }
    }

    fun recordHit(hit: WatchHit) {
        _hits.value = (listOf(hit) + _hits.value).take(MAX_HITS)
    }

    fun clearHits() {
        _hits.value = emptyList()
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun load(): List<WatchRule> {
        val raw = prefs.getString(KEY_RULES, null)
            ?: return WatchRule.defaults().also { seed -> persist(seed) }
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.getJSONObject(index)
                val kind = runCatching { MatchKind.valueOf(obj.getString("kind")) }.getOrNull()
                    ?: return@mapNotNull null
                WatchRule(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    kind = kind,
                    value = obj.getString("value"),
                    enabled = obj.optBoolean("enabled", true),
                    minRssi = obj.optInt("minRssi", -100),
                    cooldownSeconds = obj.optInt("cooldownSeconds", 180),
                    notify = obj.optBoolean("notify", true),
                )
            }
        }.getOrDefault(WatchRule.defaults())
    }

    private fun save() = persist(_rules.value)

    private fun persist(rules: List<WatchRule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("label", rule.label)
                    .put("kind", rule.kind.name)
                    .put("value", rule.value)
                    .put("enabled", rule.enabled)
                    .put("minRssi", rule.minRssi)
                    .put("cooldownSeconds", rule.cooldownSeconds)
                    .put("notify", rule.notify),
            )
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    companion object {
        private const val KEY_RULES = "rules"
        private const val KEY_ARMED = "armed"
        private const val MAX_HITS = 200

        @Volatile
        private var instance: WatchStore? = null

        fun get(context: Context): WatchStore =
            instance ?: synchronized(this) {
                instance ?: WatchStore(context).also { instance = it }
            }
    }
}
