package com.example.data.repository

import android.content.Context
import com.example.data.model.P2PTransferRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class P2PTransferRepository(context: Context) {
    private val prefs = context.getSharedPreferences("p2p_transfer_history", Context.MODE_PRIVATE)
    private val _transfers = MutableStateFlow<List<P2PTransferRecord>>(emptyList())
    val transfers: StateFlow<List<P2PTransferRecord>> = _transfers.asStateFlow()

    init {
        loadTransfers()
    }

    private fun loadTransfers() {
        val raw = prefs.getString("transfers_json", null) ?: "[]"
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<P2PTransferRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    P2PTransferRecord(
                        id = obj.getString("id"),
                        mediaId = obj.optString("mediaId", obj.getString("id")),
                        title = obj.getString("title"),
                        posterUrl = obj.optString("posterUrl", ""),
                        isMovie = obj.optBoolean("isMovie", true),
                        isReceived = obj.optBoolean("isReceived", true),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        deviceName = obj.optString("deviceName", ""),
                        quality = obj.optString("quality", ""),
                        contentType = obj.optString("contentType", "")
                    )
                )
            }
            _transfers.value = list
        } catch (e: Exception) {
            _transfers.value = emptyList()
        }
    }

    fun recordTransfer(record: P2PTransferRecord) {
        val current = _transfers.value.toMutableList()
        current.removeAll { it.id == record.id && it.isReceived == record.isReceived }
        current.add(0, record)
        _transfers.value = current
        saveTransfers(current)
    }

    private fun saveTransfers(list: List<P2PTransferRecord>) {
        val arr = JSONArray()
        list.take(50).forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("mediaId", item.mediaId)
            obj.put("title", item.title)
            obj.put("posterUrl", item.posterUrl)
            obj.put("isMovie", item.isMovie)
            obj.put("isReceived", item.isReceived)
            obj.put("timestamp", item.timestamp)
            obj.put("deviceName", item.deviceName)
            obj.put("quality", item.quality)
            obj.put("contentType", item.contentType)
            arr.put(obj)
        }
        prefs.edit().putString("transfers_json", arr.toString()).apply()
    }

    fun clearAll() {
        _transfers.value = emptyList()
        prefs.edit().remove("transfers_json").apply()
    }

    fun deleteTransfer(id: String, isReceived: Boolean) {
        val current = _transfers.value.toMutableList()
        current.removeAll { it.id == id && it.isReceived == isReceived }
        _transfers.value = current
        saveTransfers(current)
    }
}
