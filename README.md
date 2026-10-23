# AKULA VPN

Приложение для подключения к своему VPN-серверу AKULA: все типы подключений в одном приложении.

- **AmneziaWG / WireGuard** — быстрый и хорошо маскируемый основной режим;
- **OpenVPN** — UDP и TCP (движок OpenVPN for Android);
- **режим для белых списков** — туннель под видом видеозвонка (olcRTC); временная возможность, без гарантий;
- **ссылка-подписка** из консоли сервера: подключения добавляются и обновляются сами;
- автопереключение серверов (включается в настройках), язык — как в системе / русский / English.

Основано на [olcbox](https://github.com/alananisimov/olcbox) (Kotlin Multiplatform + Compose). Платформы: Android
(готово), Windows (готово: AmneziaWG и OpenVPN внутри программы).

*English:* AKULA VPN is a client for self-hosted AKULA VPN servers — AmneziaWG/WireGuard, OpenVPN and an
olcRTC-based mode in one app, with subscription links. Based on olcbox. Licensed under GPL v3.

## Сборка (Android)

Нужны Docker, Android SDK и JDK 17+ (например, из Android Studio).

1. Библиотека AmneziaWG (собирается в Linux-контейнере):
   ```sh
   docker build -t akula/awg-tunnel tools/awg-tunnel
   docker create --name awg akula/awg-tunnel && docker cp awg:/out/tunnel-release.aar sharedUI/awg-bin/amneziawg-tunnel.aar && docker rm awg
   ```
2. Движок OpenVPN (ics-openvpn, вариант skeleton + ovpn2):
   ```sh
   docker build -t akula/ics-openvpn -f tools/ics-openvpn/Dockerfile tools/ics-openvpn
   docker create --name ovpn akula/ics-openvpn && docker cp ovpn:/out/ics-openvpn.aar sharedUI/ovpn-bin/ && docker rm ovpn
   ```
3. olcRTC (Go + gomobile) — репозиторий [olcrtc](https://github.com/openlibrecommunity/olcrtc) рядом с этим
   (`../olcrtc`) или путь в `OLCRTC_REPO`; собирается задачей `:sharedUI:olcrtc-bin:buildOlcrtcAndroidAar`.
4. Приложение:
   ```sh
   ./gradlew :androidApp:assembleRelease
   ```
   Подпись релиза — свой ключ: файл свойств по пути из `AKULA_KEYSTORE_PROPERTIES` или
   `~/.akula-secrets/android-signing/akula-vpn-keystore.properties` (в репозитории ключей нет).

## Сборка (Windows)

Нужны JDK 21 (с jpackage), Go, MinGW-w64 (gcc) и WiX 3.14 в `PATH`.

```sh
./gradlew :desktopApp:packageAkulaExe :desktopApp:packageAkulaMsi -x :sharedUI:olcrtc-bin:buildOlcrtcAndroidAar
```

Установщики — `desktopApp/build/akula-installers/{exe,msi}/AKULA-VPN-windows.{exe,msi}`.

Всё в одной программе, без отдельных VPN-клиентов. При сборке скачиваются официальные подписанные модули
(суммы SHA-256 зашиты в `desktopApp/build.gradle.kts`): AmneziaWG для Windows (amneziawg.exe + Wintun),
OpenVPN 2.7 (openvpn.exe + OpenSSL) и драйвер ovpn-dco. При подключении программа (с правами администратора)
копирует их в `C:/ProgramData/AKULA VPN/vpn` — туда пишут только администраторы, — проверяет цифровые подписи и
запускает оттуда: программу можно ставить в любую папку, подменить модули из папки пользователя нельзя.
Установщик — свой шаблон WiX (`desktopApp/wix/main.wxs`): при удалении и обновлении гасит туннели.

## Выпуск обновления

Приложения раз в сутки смотрят последний релиз этого репозитория (`/releases/latest`) и предлагают обновиться,
если его версия новее установленной.

1. Поднять `olcbox.version` / `olcbox.versionCode` в `gradle.properties`.
2. Собрать: Android — `./gradlew :androidApp:assembleRelease` (тем же ключом, иначе обновление не встанет),
   Windows — `./gradlew :desktopApp:packageMsi` (нужен WiX 3.14 в `PATH`).
3. Релиз на GitHub с тегом `vX.Y.Z` и файлами `AKULA-VPN-android.apk` и `AKULA-VPN-windows.exe` — имена без
   версии: постоянные ссылки `https://github.com/Mr-AKULA/akula-vpn/releases/latest/download/<файл>` (сайт, консоль
   сервера) всегда ведут на свежую сборку; по `android` / `windows` в имени приложение выбирает файл обновления.
4. Сборки пустые: ни профилей, ни адресов серверов, ни ключей.
5. Для старых версий Windows — архив `AKULA-VPN-windows-amd64.zip` (внутри `AKULA-VPN-windows.exe` и
   «Как обновить.txt»). Версии 1.2.1–1.2.4 берут файл с «windows» и «amd64» в имени и не умеют запускать
   .exe/.msi (Java: «Unsupported URI content»), а архив открывают в Проводнике — человек видит установщик.
   **Не класть** `.exe`/`.msi` с «amd64» в имени — старые версии выберут его и снова не смогут запустить.
   С 1.2.5 программа сама берёт `AKULA-VPN-windows.exe` и запускает его. Обновление определяется номером
   версии — дозаливать файлы в уже выпущенный релиз можно, повторно его не предложат (с 1.2.3).
6. Локальная сборка Windows: JDK 21, MinGW и WiX лежат в `C:\Users\AKULA\.local`; Gradle — с `--no-configuration-cache`.

## Лицензия

GPL v3 (см. `LICENSE`). Компоненты и их лицензии — в `NOTICE` и папке `licenses/`.
