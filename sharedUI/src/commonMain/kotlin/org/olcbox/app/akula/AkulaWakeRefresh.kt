package org.olcbox.app.akula

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.repository.LocationsRepository

/**
 * AKULA (Павел 23.10.2026): почта сервера и код для белых списков приходят в подписке, но подписка
 * обновляется по расписанию — подключение, скачанное раньше, их ещё не знает, и письмо не уходит.
 * Перед подключением к белым спискам AKULA без этих данных сначала обновляем подписку.
 */
suspend fun LocationsRepository.activeLocationWithWake(log: (String) -> Unit): LocationEntry? {
    val active = getActiveLocation() ?: return null
    val location = active.location
    val url = active.subscriptionUrl
    val akulaWhitelist = active.storageId.startsWith("akula_") &&
        location.bypassProvider == LocationConfig.PROVIDER_JITSI && !location.isAwg && !location.isOvpn
    if (!akulaWhitelist || location.hasServerMailbox || url.isNullOrBlank()) return active
    log("AKULA: в подключении нет ящика сервера — обновляю подписку")
    runCatching { refreshSubscription(url) }
        .onFailure { log("AKULA: подписка не обновилась: ${it.message}") }
    return getActiveLocation() ?: active
}
