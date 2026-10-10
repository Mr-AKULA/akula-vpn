package org.olcbox.app.akula

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl

/**
 * AKULA: своя сборка у каждого (Павел, Настя) — в assets/akula_preset.json ссылка подключения и
 * настройки пробуждения. При первом запуске (и при каждой новой версии вшитых настроек — один раз)
 * подключение добавляется и становится активным: вписывать ничего не нужно, менять — можно.
 */
object AkulaPreset {
    private const val ASSET = "akula_preset.json"
    private const val PREFS = "akula_preset"

    fun seed(context: Context) {
        val raw = runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull() ?: return
        val sig = raw.hashCode().toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("applied", null) == sig) return
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val link = j.optString("link").trim()
        if (link.isEmpty()) return
        val room = link.substringAfter('@', "").substringBefore('#').trim()
        runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val repo = LocationsRepositoryImpl(LocationsDataSourceImpl(context))
                    val key = room.trimEnd('/').substringAfterLast('/').lowercase()
                    suspend fun find() = repo.getAllLocations()
                        .firstOrNull { it.location.id.trimEnd('/').substringAfterLast('/').lowercase() == key }
                    if (find() == null) repo.importText(link)
                    find()?.let { repo.setActiveLocationId(it.storageId) }
                }
            }
            val s = AkulaWakeSettings(j.optString("email"), j.optString("password"), j.optString("code"))
            if (s.ok()) AkulaWake.save(context, room, s)
            prefs.edit().putString("applied", sig).apply()
        }
    }
}
