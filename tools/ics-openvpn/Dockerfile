# Движок OpenVPN 2 для AKULA VPN — библиотека (.aar) из ics-openvpn (вариант skeleton + ovpn2), сборка в Linux.
# Лицензия ics-openvpn — GPL v2 «или любой более поздней» → AKULA VPN выпускается под GPL v3 (решение Павла 16.10.2026).
#   docker build -t akula/ics-openvpn -f tools/ics-openvpn/Dockerfile tools/ics-openvpn
#   docker create --name ovpn akula/ics-openvpn && docker cp ovpn:/out/ics-openvpn.aar sharedUI/ovpn-bin/ && docker rm ovpn
FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update && apt-get install -y --no-install-recommends git make curl unzip cmake ninja-build python3 swig \
        gcc g++ libc6-dev patch \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
# Только инструменты и лицензии — нужные платформу, NDK и CMake Gradle скачает сам по build.gradle.kts
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && curl -sSL -o /tmp/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/ct.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/ct.zip && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null

# Закреплённая версия ics-openvpn (14445f1, 16.10.2026) с подмодулями (openvpn, openssl, mbedtls, lz4, …)
ARG ICS_REF=14445f1
RUN git clone https://github.com/schwabe/ics-openvpn.git /src && cd /src && git checkout -q $ICS_REF \
    && git submodule update --init --recursive --depth 1

COPY to_library.py /tmp/to_library.py
WORKDIR /src
RUN python3 /tmp/to_library.py main/build.gradle.kts gradle/libs.versions.toml \
    && ./gradlew --no-daemon -q :main:assembleSkeletonOvpn2Release \
    && mkdir -p /out && cp main/build/outputs/aar/*skeleton*ovpn2*release*.aar /out/ics-openvpn.aar
