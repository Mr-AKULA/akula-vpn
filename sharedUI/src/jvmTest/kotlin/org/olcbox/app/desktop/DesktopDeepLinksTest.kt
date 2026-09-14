package org.olcbox.app.desktop

import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDeepLinksTest {
    @Test
    fun coldLaunchAndSecondLaunchReachTheSameReceiver() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("olcbox-deep-link-test")
        val first = "olcbox://add?url=https%3A%2F%2Fexample.org%2Ffirst"
        val second = "olcbox://add?url=https%3A%2F%2Fexample.org%2Fsecond"
        try {
            DesktopDeepLinks.open(arrayOf(first), directory)!!.use { app ->
                assertEquals(first, withTimeout(2_000) { app.events.first() })
                assertNull(DesktopDeepLinks.open(arrayOf(second), directory))
                assertEquals(second, withTimeout(2_000) { app.events.first() })
                assertNull(DesktopDeepLinks.open(emptyArray(), directory))
                assertEquals("", withTimeout(2_000) { app.events.first() })
            }
            DesktopDeepLinks.open(emptyArray(), directory).use { assertNotNull(it) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktopEntryPassesUrlAsOneArgumentAndEscapesLauncherPath() {
        val entry = DesktopDeepLinkRegistration.linuxDesktopEntry("/home/user/My Apps/Olcbox 100%.AppImage")
        assertTrue("Exec=\"/home/user/My Apps/Olcbox 100%%.AppImage\" %u\n" in entry)
        assertTrue("MimeType=x-scheme-handler/olcbox;" in entry)
    }
}
