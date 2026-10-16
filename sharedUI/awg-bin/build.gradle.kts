// Готовая библиотека AmneziaWG для Android (tunnel.aar) — собирается в Linux-контейнере:
//   docker build -t akula/awg-tunnel tools/awg-tunnel
//   docker create --name awg akula/awg-tunnel && docker cp awg:/out/tunnel-release.aar sharedUI/awg-bin/amneziawg-tunnel.aar && docker rm awg
// Так же, как olcrtc-bin: модуль только отдаёт файл .aar.
configurations.maybeCreate("default")
artifacts.add("default", file("amneziawg-tunnel.aar"))
