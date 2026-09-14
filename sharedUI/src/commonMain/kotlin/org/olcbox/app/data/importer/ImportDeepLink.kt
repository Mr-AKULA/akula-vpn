package org.olcbox.app.data.importer

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** olcbox://add?url=<percent-encoded HTTP(S) subscription URL>. Decode exactly once. */
object ImportDeepLink {
    const val MAX_LENGTH = 16_384

    fun parse(value: String): String? = runCatching {
        require(value.length <= MAX_LENGTH && value.all { it.code in 33..126 })
        val route = value.substringBefore('?')
        require(route.equals("olcbox://add", ignoreCase = true) ||
            route.equals("olcbox://add/", ignoreCase = true))
        require('?' in value && '#' !in value)
        val query = value.substringAfter('?')
        require(query.startsWith("url=") && '&' !in query)
        val encoded = query.removePrefix("url=")
        val bytes = ByteArray(encoded.length)
        var source = 0
        var target = 0
        while (source < encoded.length) {
            bytes[target++] = when (val char = encoded[source++]) {
                '%' -> {
                    require(source + 1 < encoded.length)
                    val high = encoded[source++].digitToInt(16)
                    val low = encoded[source++].digitToInt(16)
                    ((high shl 4) or low).toByte()
                }
                '+' -> ' '.code.toByte()
                else -> char.code.toByte()
            }
        }
        val url = bytes.decodeToString(endIndex = target, throwOnInvalidSequence = true)
        require(url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        require(authority.isNotBlank() && authority.substringAfterLast('@').isNotBlank())
        require(url.none { it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\' })
        url.forEachIndexed { index, char ->
            if (char == '%') {
                require(index + 2 < url.length && url[index + 1].isHexDigit() && url[index + 2].isHexDigit())
            }
        }
        require(Url(url).host.isNotBlank())
        url
    }.getOrNull()

    private fun Char.isHexDigit(): Boolean = digitToIntOrNull(16) != null
}

/** Holds an OS launch event until the UI is ready; opening a link never imports it. */
class ImportLinkInbox {
    // Reference identity lets the same URL be opened again, including after dismissal.
    class Request(val url: String?)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending = _pending.asStateFlow()

    fun open(uri: String) {
        _pending.value = Request(ImportDeepLink.parse(uri))
    }

    fun consume(request: Request) {
        _pending.compareAndSet(request, null)
    }
}
