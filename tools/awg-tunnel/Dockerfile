# Сборка библиотеки AmneziaWG (tunnel.aar) для AKULA VPN — в Linux-контейнере:
# её Go-часть собирается make + CMake, на Windows так не собрать. Исходники клонируются внутрь контейнера
# (не с диска G: сотни тысяч файлов сборки его кладут). Наружу — только готовый .aar.
#   docker build -t akula/awg-tunnel tools/awg-tunnel
#   docker create --name awg akula/awg-tunnel && docker cp awg:/out/tunnel-release.aar sharedUI/libs/ && docker rm awg
FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && apt-get install -y --no-install-recommends git make curl unzip cmake ninja-build python3 \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && curl -sSL -o /tmp/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/ct.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/ct.zip \
    && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
    && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;26.1.10909125" "cmake;3.22.1" >/dev/null

# Закреплённая версия amneziawg-android (ff15093, 3.1.4) с подмодулями
ARG AWG_REF=ff15093
RUN git clone --recursive https://github.com/amnezia-vpn/amneziawg-android.git /src \
    && cd /src && git checkout -q $AWG_REF && git submodule update --init --recursive

# Сборке нужны компиляторы C/C++ (elf-cleaner) и patch (правка Go под Android) — 16.10.2026 без них падала
RUN apt-get update && apt-get install -y --no-install-recommends gcc g++ libc6-dev patch \
    && rm -rf /var/lib/apt/lists/*

# Пакет приложения AKULA VPN — библиотека прописывает его в пути (/data/data/<пакет>/cache)
ENV ANDROID_PACKAGE_NAME=ru.akula.vpn
WORKDIR /src
RUN ./gradlew --no-daemon -q :tunnel:assembleRelease \
    && mkdir -p /out && cp tunnel/build/outputs/aar/tunnel-release.aar /out/
