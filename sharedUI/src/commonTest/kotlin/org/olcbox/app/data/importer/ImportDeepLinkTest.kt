package org.olcbox.app.data.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class ImportDeepLinkTest {
    @Test
    fun decodesOnceAndPreservesSubscriptionQueryAndEscapes() {
        assertEquals(
            "https://example.org/sub?token=a+b%2Fc&name=Тест#section",
            ImportDeepLink.parse("olcbox://add?url=https%3A%2F%2Fexample.org%2Fsub%3Ftoken%3Da%2Bb%252Fc%26name%3D%D0%A2%D0%B5%D1%81%D1%82%23section")
        )
        assertEquals("http://192.168.1.1/sub", ImportDeepLink.parse("OLCBOX://ADD/?url=http%3A%2F%2F192.168.1.1%2Fsub"))
    }

    @Test
    fun rejectsMalformedAmbiguousAndUnsupportedLinks() {
        listOf(
            "olcbox://add", "olcbox://add?url=", "olcbox://other?url=https%3A%2F%2Fexample.org",
            "olcbox://add/config?url=https%3A%2F%2Fexample.org",
            "olcbox://add?url=https%3A%2F%2Fexample.org&url=https%3A%2F%2Fother.org",
            "olcbox://add?url=https%3A%2F%2Fexample.org&auto=true",
            "olcbox://add?url=https%3A%2F%2Fexample.org#ignored",
            "olcbox://add?url=file%3A%2F%2F%2Ftmp%2Fconfig", "olcbox://add?url=javascript%3Aalert(1)",
            "olcbox://add?url=https%253A%252F%252Fexample.org", "olcbox://add?url=https%3A%2F%2F",
            "olcbox://add?url=https%3A%2F%2Fexample.org%2F%", "olcbox://add?url=https%3A%2F%2Fexample.org%2F%GG",
            "olcbox://add?url=https%3A%2F%2Fexample.org%2F%FF", "olcbox://add?url=https%3A%2F%2Fexample.org%0A",
            "olcbox://add?url=https%3A%2F%2Fexample.org%2Fa+b", "olcbox://add?url=https%3A%2F%2Fexample.org%5Cevil",
            "olcbox://add?url=https%3A%2F%2Fexample.org%2F%25GG", "x".repeat(ImportDeepLink.MAX_LENGTH + 1)
        ).forEach { assertNull(ImportDeepLink.parse(it), it.take(100)) }
    }

    @Test
    fun retainsLaunchUntilUiConsumesItAndAllowsRepeatedLinks() {
        val inbox = ImportLinkInbox()
        val link = "olcbox://add?url=https%3A%2F%2Fexample.org%2Fsub"
        inbox.open(link)
        val first = inbox.pending.value!!
        assertEquals("https://example.org/sub", first.url)
        inbox.open(link)
        val second = inbox.pending.value!!
        assertNotSame(first, second)
        inbox.consume(first)
        assertSame(second, inbox.pending.value)
        inbox.consume(second)
        assertNull(inbox.pending.value)
        inbox.open("olcbox://unsupported")
        assertNull(inbox.pending.value!!.url)
    }
}
