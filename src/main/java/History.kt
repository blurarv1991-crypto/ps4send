package com.ps4send.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val mode: String,
    val name: String,
    val dest: String,
    val uri: String,
    val time: Long,
    val ok: Boolean
)

object History {
    private const val PREF = "history"
    private const val KEY = "items"
    private const val MAX = 50

    fun all(ctx: Context): List<HistoryItem> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(
                o.getString("mode"), o.getString("name"), o.getString("dest"),
                o.optString("uri", ""), o.getLong("time"), o.getBoolean("ok")
            )
        }
    }

    fun add(ctx: Context, item: HistoryItem) {
        save(ctx, (listOf(item) + all(ctx)).take(MAX))
    }

    fun remove(ctx: Context, item: HistoryItem) {
        save(ctx, all(ctx).filter { it != item })
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("mode", it.mode).put("name", it.name)
                    .put("dest", it.dest).put("uri", it.uri)
                    .put("time", it.time).put("ok", it.ok)
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
