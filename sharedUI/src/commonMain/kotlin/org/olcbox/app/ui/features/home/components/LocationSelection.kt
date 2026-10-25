package org.olcbox.app.ui.features.home.components

import org.olcbox.app.ui.i18n.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import org.olcbox.app.data.model.SubscriptionPrefs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.parseTrafficQuota
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LocationRow
import org.olcbox.app.ui.features.locations.components.RefreshButton
import org.olcbox.app.ui.components.TrafficQuotaIndicator

@Composable
fun LocationSelectorScreen(
    modifier: Modifier = Modifier,
    onRefreshClick: (targetLocationIds: List<String>) -> Unit,
    onSubscriptionUpdateClick: (subscriptionUrl: String) -> Unit,
    updatingSubscriptionUrl: String?,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    locations: List<LocationItem>,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    subscriptionPrefs: Map<String, SubscriptionPrefs> = emptyMap(),
    onToggleSubscriptionCollapsed: (subscriptionUrl: String) -> Unit = {},
    onRenameSubscription: (subscriptionUrl: String, name: String?) -> Unit = { _, _ -> },
    onHideLocation: (storageId: String) -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val subscriptionLocations = locations.filter { !it.subscriptionUrl.isNullOrBlank() }
        val subscriptionGroups = subscriptionLocations
            .groupBy { it.subscriptionGroupKey() }
            .values
            .toList()
        val customLocations = locations.filter { it.subscriptionUrl.isNullOrBlank() }

        if (locations.isEmpty()) {
            RelaySetupCard(
                onAddSubscriptionClick = onAddSubscriptionClick,
                onAddLocationClick = onAddLocationClick
            )
            return@Column
        }

        // Переименование подписки: адрес подписки, для которой открыто окно
        var renameUrl by remember { mutableStateOf<String?>(null) }
        var renameText by remember { mutableStateOf("") }
        renameUrl?.let { url ->
            AlertDialog(
                onDismissRequest = { renameUrl = null },
                title = { Text(tr("Название подписки", "Subscription name")) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it.take(60) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = tr(
                                "Только на этом устройстве. Пустое — название с сервера.",
                                "This device only. Empty — the name from the server."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onRenameSubscription(url, renameText); renameUrl = null }) {
                        Text(tr("Сохранить", "Save"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameUrl = null }) { Text(tr("Отмена", "Cancel")) }
                }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            subscriptionGroups.forEach { group ->
                val groupUrl = group.firstOrNull()?.subscriptionUrl?.trim().orEmpty()
                val prefs = subscriptionPrefs[groupUrl]
                val collapsed = prefs?.collapsed == true
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Название — своей строкой (рядом с кнопками на телефоне оно не помещалось), кнопки — под ним
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Нажатие на название или стрелку — свернуть/развернуть группу (свёрнутая — только название)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = groupUrl.isNotBlank()) { onToggleSubscriptionCollapsed(groupUrl) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                                contentDescription = if (collapsed) tr("Развернуть", "Expand") else tr("Свернуть", "Collapse"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            SubscriptionGroupHeader(
                                locations = group,
                                customName = prefs?.customName,
                                collapsedCount = group.size.takeIf { collapsed },
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (groupUrl.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        renameText = prefs?.customName.orEmpty()
                                        renameUrl = groupUrl
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = tr("Переименовать", "Rename"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        val groupIds = group.map { it.storageId }
                        val isGroupRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in groupIds }

                        if (!collapsed) Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RefreshButton(
                                isRefreshing = isGroupRefreshing,
                                onClick = { onRefreshClick(groupIds) },
                                tint = MaterialTheme.colorScheme.primary,
                                label = tr("Проверить", "Check"),
                                icon = Icons.Outlined.Bolt,
                                enabled = updatingSubscriptionUrl == null
                            )

                            group.firstOrNull()
                                ?.subscriptionUrl
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { subscriptionUrl ->
                                    RefreshButton(
                                        isRefreshing = updatingSubscriptionUrl == subscriptionUrl,
                                        onClick = { onSubscriptionUpdateClick(subscriptionUrl) },
                                        tint = MaterialTheme.colorScheme.primary,
                                        label = tr("Обновить", "Update"),
                                        icon = Icons.Outlined.Refresh,
                                        enabled = updatingSubscriptionUrl == null && !isGroupRefreshing
                                    )
                                }
                        }
                    }

                    if (!collapsed) {
                        val subscription = group.firstOrNull()?.metadata?.subscription
                        TrafficQuotaIndicator(
                            used = subscription?.used,
                            available = subscription?.available,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                        )

                        val hiddenCount = prefs?.hiddenIds?.size ?: 0
                        if (hiddenCount > 0) {
                            Text(
                                text = tr(
                                    "Убрано из списка: $hiddenCount. Вернуть — «Обновить»",
                                    "Removed from list: $hiddenCount. Restore — \"Update\""
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            group.forEach { location ->
                                LocationSelectorRow(
                                    location = location,
                                    selectedLocationId = selectedLocationId,
                                    pingsState = pingsState,
                                    onLocationSelected = onLocationSelected,
                                    onLocationSettingsClick = onLocationSettingsClick,
                                    onHideClick = { onHideLocation(location.storageId) }
                                )
                            }
                        }
                    }
                }
            }

            if (customLocations.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LocationGroupHeader(
                            title = tr("Мои подключения", "My connections"),
                            modifier = Modifier.weight(1f)
                        )

                        // 2. Вычисляем состояние загрузки только для кастомных локаций
                        val customIds = customLocations.map { it.storageId }
                        val isCustomRefreshing = pingsState is PingsState.Loading &&
                                pingsState.pendingLocationIds.any { it in customIds }

                        RefreshButton(
                            isRefreshing = isCustomRefreshing,
                            onClick = { onRefreshClick(customIds) },
                            tint = MaterialTheme.colorScheme.primary,
                            label = tr("Проверить", "Check"),
                            icon = Icons.Outlined.Bolt
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        customLocations.forEach { location ->
                            LocationSelectorRow(
                                location = location,
                                selectedLocationId = selectedLocationId,
                                pingsState = pingsState,
                                onLocationSelected = onLocationSelected,
                                onLocationSettingsClick = onLocationSettingsClick
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onAddLocationClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tr("Добавить вручную", "Add manually"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (subscriptionLocations.isEmpty()) {
                FilledTonalButton(
                    onClick = onAddSubscriptionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tr("Добавить по ссылке", "Add via link"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun RelaySetupCard(
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = tr("Добавить подключение", "Add connection"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp)
        )

        SetupActionRow(
            title = tr("Добавить по ссылке", "Add via link"),
            subtitle = tr("Сканировать QR, вставить ссылку или открыть файл", "Scan QR, paste URI, or import file"),
            icon = Icons.Outlined.QrCodeScanner,
            prominent = true,
            onClick = onAddSubscriptionClick
        )

        SetupActionRow(
            title = tr("Новое подключение", "Create custom location"),
            subtitle = tr("Комната, ключ, сервис и способ передачи", "Enter room, key, provider, and transport"),
            icon = Icons.Outlined.Add,
            onClick = onAddLocationClick
        )
    }
}

@Composable
private fun SetupActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val borderColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (prominent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (prominent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocationGroupHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SubscriptionGroupHeader(
    locations: List<LocationItem>,
    customName: String?,
    collapsedCount: Int?,
    modifier: Modifier = Modifier
) {
    val first = locations.firstOrNull()
    val title = customName?.takeIf { it.isNotBlank() }
        ?: first?.subscriptionTitle().orEmpty().ifBlank { tr("Подписки", "Subscriptions") }
    val description = first?.subscriptionDescription()
    val details = first?.subscriptionDetails()

    Column(modifier = modifier.padding(start = 4.dp, top = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (collapsedCount != null) {
            Text(
                text = tr("Подключений: $collapsedCount", "Connections: $collapsedCount"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            return@Column
        }

        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!details.isNullOrBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LocationSelectorRow(
    location: LocationItem,
    selectedLocationId: String?,
    pingsState: PingsState,
    onLocationSelected: (String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    onHideClick: (() -> Unit)? = null
) {
    val pingMs = pingsState.pingFor(location.storageId)
    val isLoading = pingsState.isChecking(location.storageId)
    val isOffline = pingsState.isOffline(location.storageId)

    LocationRow(
        location = location,
        isSelected = selectedLocationId == location.storageId,
        isLoading = isLoading,
        isError = isOffline,
        pingMs = pingMs,
        onSettingsClick = {
            onLocationSettingsClick(location.storageId)
        },
        onHideClick = onHideClick,
        onClick = {
            onLocationSelected(location.storageId)
        }
    )
}

private fun PingsState.pingFor(locationId: String): Int? {
    return when (this) {
        PingsState.Idle -> null

        is PingsState.Loading -> {
            if (currentPings.containsKey(locationId)) {
                currentPings[locationId]
            } else {
                lastPings?.get(locationId)
            }
        }

        is PingsState.Success -> {
            pings[locationId]
        }

        is PingsState.Error -> {
            lastPings?.get(locationId)
        }
    }
}

private fun PingsState.isChecking(locationId: String): Boolean {
    return this is PingsState.Loading && locationId in pendingLocationIds
}

private fun PingsState.isOffline(locationId: String): Boolean {
    return when (this) {
        PingsState.Idle -> false

        is PingsState.Loading -> {
            currentPings.containsKey(locationId) && currentPings[locationId] == null
        }

        is PingsState.Success -> {
            pings.containsKey(locationId) && pings[locationId] == null
        }

        is PingsState.Error -> false
    }
}

private fun LocationItem.subscriptionGroupKey(): String {
    return listOfNotNull(
        metadata?.subscription?.name?.takeIf { it.isNotBlank() },
        subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("|").ifBlank { storageId }
}

private fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription

    return listOfNotNull(
        subscription?.icon?.takeIf { it.isNotBlank() },
        subscription?.name?.takeIf { it.isNotBlank() } ?: tr("Подписки", "Subscriptions")
    ).joinToString(" ")
}

private fun LocationItem.subscriptionDetails(): String? {
    val subscription = metadata?.subscription ?: return null
    val hasProgressQuota = parseTrafficQuota(subscription.used, subscription.available) != null

    return listOfNotNull(
        quotaText(subscription.used, subscription.available).takeUnless { hasProgressQuota },
        subscription.refresh?.takeIf { it.isNotBlank() }?.let { "Refresh $it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun LocationItem.subscriptionDescription(): String? {
    val subscription = metadata?.subscription ?: return null
    return subscription.displayDescription()
}

private fun quotaText(used: String?, available: String?): String? {
    return when {
        !used.isNullOrBlank() && !available.isNullOrBlank() -> "$used used · $available available"
        !used.isNullOrBlank() -> "$used used"
        !available.isNullOrBlank() -> "$available available"
        else -> null
    }
}

private fun plural(value: Long, unit: String): String {
    return "$value $unit${if (value == 1L) "" else "s"}"
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
