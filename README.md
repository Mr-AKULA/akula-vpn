# AKULA VPN

Приложение для подключения к своему VPN-серверу AKULA: все типы подключений в одном приложении.

- **AmneziaWG / WireGuard** — быстрый и хорошо маскируемый основной режим;
- **OpenVPN** — UDP и TCP (движок OpenVPN for Android);
- **режим для белых списков** — туннель под видом видеозвонка (olcRTC); временная возможность, без гарантий;
- **ссылка-подписка** из консоли сервера: подключения добавляются и обновляются сами;
- автопереключение серверов (включается в настройках), язык — как в системе / русский / English.

Основано на [olcbox](https://github.com/alananisimov/olcbox) (Kotlin Multiplatform + Compose). Платформы: Android
(готово), Windows (AmneziaWG — готово, OpenVPN — в работе).

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

Нужны JDK 21 (с jpackage), Go и MinGW-w64 (gcc) в `PATH`.

```sh
./gradlew :desktopApp:createDistributable -x :sharedUI:olcrtc-bin:buildOlcrtcAndroidAar
```

Готовая программа — `desktopApp/build/compose/binaries/main/app/AKULA VPN/`. AmneziaWG на Windows работает через
официальную AmneziaWG (MIT, подписанный драйвер): при сборке скачивается её установщик 3.1.0 с проверкой SHA-256,
а при первом подключении AKULA VPN ставит его тихо и поднимает туннель её службой (нужны права администратора —
программа перезапускается с запросом UAC). OpenVPN на компьютере — в работе.

## Лицензия

GPL v3 (см. `LICENSE`). Компоненты и их лицензии — в `NOTICE` и папке `licenses/`.
