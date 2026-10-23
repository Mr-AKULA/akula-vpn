package org.olcbox.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.akula.AkulaMailAccount
import org.olcbox.app.akula.AkulaMailAccountStore
import org.olcbox.app.ui.i18n.tr

/** Подпись строки в списке настроек: указана ли своя почта для включения белых списков */
fun akulaMailSummary(): String {
    val account = AkulaMailAccountStore.load()
    return if (account.ok()) account.email else tr("Не указана — письмо откроется в почте", "Not set — the e-mail opens in your mail app")
}

/**
 * AKULA: своя почта Яндекса — с неё приложение само шлёт письмо «включить/выключить» серверу белых списков.
 * Почта сервера и код приходят в подписке; здесь — только отправитель.
 */
@Composable
fun AkulaMailSettingsBody(modifier: Modifier = Modifier) {
    val saved = remember { AkulaMailAccountStore.load() }
    var email by remember { mutableStateOf(saved.email) }
    var password by remember { mutableStateOf(saved.password) }
    var savedNow by remember { mutableStateOf(saved) }
    val edited = AkulaMailAccount(email, password).normalized()
    // Домен не проверяем: почта на своём домене в Яндекс 360 тоже ходит через smtp.yandex.ru
    val emailValid = email.isBlank() || (email.contains('@') && email.substringAfter('@').contains('.'))
    val canSave = edited != savedNow && (edited.ok() || (email.isBlank() && password.isBlank()))

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = tr(
                "Белые списки (звонок) спят, пока не нужны. «Подключить» отправит письмо «включить» на почту сервера, " +
                    "«Отключить» — «выключить» (сервер выключится, когда в звонке никого не останется). " +
                    "Почта сервера и код приходят в подписке — вводить их не нужно.",
                "Whitelist calls sleep until needed. Connect sends a wake e-mail to the server mailbox, Disconnect sends " +
                    "a stop e-mail (the server stops once nobody is left in the call). The server mailbox and code come " +
                    "with the subscription."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
        Text(
            text = tr(
                "Укажите свою почту Яндекса и пароль приложения (id.yandex.ru → Безопасность → Пароли приложений → Почта). " +
                    "Только Яндекс: при белых списках мобильного интернета работает только он. " +
                    "Без почты «Подключить» откроет готовое письмо — останется нажать «Отправить».",
                "Enter your Yandex mail and an app password (id.yandex.ru → Security → App passwords → Mail). " +
                    "Yandex only: it is the one mail that works under mobile whitelists. " +
                    "Without it, Connect opens a ready e-mail — just press Send."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.replace("\n", "").trim().take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("Своя почта Яндекса", "Your Yandex mail")) },
            placeholder = { Text("name@yandex.ru") },
            singleLine = true,
            isError = !emailValid,
            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
            supportingText = if (!emailValid) {
                { Text(tr("Проверьте адрес почты", "Check the e-mail address")) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.replace("\n", "").take(64) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("Пароль приложения", "App password")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            supportingText = { Text(tr("Хранится только на этом устройстве", "Stored on this device only")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (savedNow.ok()) {
                TextButton(onClick = {
                    AkulaMailAccountStore.save(AkulaMailAccount())
                    email = ""; password = ""; savedNow = AkulaMailAccount()
                }) { Text(tr("Убрать", "Remove")) }
                Spacer(Modifier.width(8.dp))
            }
            Button(enabled = canSave && emailValid, onClick = {
                AkulaMailAccountStore.save(edited)
                savedNow = AkulaMailAccountStore.load()
            }) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Сохранить", "Save"))
            }
        }
    }
}
