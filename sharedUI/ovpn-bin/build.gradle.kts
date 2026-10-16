// Движок OpenVPN 2 для Android (ics-openvpn, вариант skeleton+ovpn2, GPL v2+) — собирается в Linux-контейнере:
//   docker build -t akula/ics-openvpn -f tools/ics-openvpn/Dockerfile tools/ics-openvpn
//   docker create --name ovpn akula/ics-openvpn && docker cp ovpn:/out/ics-openvpn.aar sharedUI/ovpn-bin/ && docker rm ovpn
// Так же, как awg-bin и olcrtc-bin: модуль только отдаёт файл .aar.
configurations.maybeCreate("default")
artifacts.add("default", file("ics-openvpn.aar"))
