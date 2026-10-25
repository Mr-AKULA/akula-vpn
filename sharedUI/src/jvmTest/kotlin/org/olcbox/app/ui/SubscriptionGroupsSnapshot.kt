package org.olcbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.SubscriptionPrefs
import org.olcbox.app.ui.features.home.components.LocationSelectorScreen
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.theme.AppTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Снимки группы подписок без окна (ImageComposeScene) — посмотреть глазами, не трогая подключения на ПК:
 * название с сервера и своё, свёрнутая группа, «Убрано из списка». PNG — в sharedUI/build/ui-snapshots.
 */
class SubscriptionGroupsSnapshot {
    private fun item(id: String, name: String, url: String, subscription: String, ovpn: Boolean = true) = LocationItem(
        storageId = id,
        fullName = name,
        config = LocationConfig(name = name, ovpnConfig = if (ovpn) "client\nremote 1.2.3.4 1194" else "",
            awgConfig = if (ovpn) "" else "[Interface]\n[Peer]\nEndpoint = 1.2.3.4:443"),
        subscriptionUrl = url,
        metadata = LocationMetadata(subscription = SubscriptionMetadata(name = subscription))
    )

    private fun render(
        name: String,
        prefs: Map<String, SubscriptionPrefs>,
        width: Int = 420,
        clickAt: Offset? = null // нажать (в пикселях снимка) и снять ещё раз — меню «⋮», окно названия
    ) {
        val a = "https://vpn.example/api/sub/a"
        val b = "https://vpn.example/api/sub/b"
        val locations = listOf(
            item("a1", "Только заблокированное", a, "ноут_1 · Сервер 1"),
            item("a2", "Весь трафик", a, "ноут_1 · Сервер 1"),
            item("b1", "Весь трафик", b, "Ноут_2 · Сервер AKULA", ovpn = false)
        ).filterNot { loc -> prefs.values.any { loc.storageId in it.hiddenIds } } // убранные отфильтровывает модель
        val image = ImageComposeScene(width, 900, Density(1.5f)) {
            AppTheme(useDynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    LocationSelectorScreen(
                        onRefreshClick = {}, onSubscriptionUpdateClick = {}, updatingSubscriptionUrl = null,
                        onAddSubscriptionClick = {}, onAddLocationClick = {},
                        locations = locations, selectedLocationId = "a1", pingsState = PingsState.Idle,
                        onLocationSelected = {}, onLocationSettingsClick = {},
                        subscriptionPrefs = prefs
                    )
                }
            }
        }.use { scene ->
            scene.render()
            if (clickAt != null) {
                scene.sendPointerEvent(PointerEventType.Press, clickAt)
                scene.sendPointerEvent(PointerEventType.Release, clickAt)
                scene.render(500_000_000)
            }
            scene.render(1_000_000_000)
        }
        val out = File("build/ui-snapshots/$name.png").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        assertTrue(out.length() > 0)
    }

    @Test
    fun serverNames() = render("1-server-names", emptyMap())

    @Test
    fun serverNamesNarrowPhone() = render("3-phone-360dp", emptyMap(), width = 540)

    @Test
    fun locationMenu() = render("4-menu", emptyMap(), width = 540, clickAt = Offset(407f, 201f))

    @Test
    fun renameDialog() = render("5-rename", mapOf(
        "https://vpn.example/api/sub/a" to SubscriptionPrefs(customName = "Мой ноутбук")
    ), width = 540, clickAt = Offset(264f, 48f))

    @Test
    fun customNameCollapsedAndHidden() = render("2-custom-collapsed", mapOf(
        "https://vpn.example/api/sub/a" to SubscriptionPrefs(customName = "Мой ноутбук", hiddenIds = listOf("a2")),
        "https://vpn.example/api/sub/b" to SubscriptionPrefs(collapsed = true)
    ))
}
