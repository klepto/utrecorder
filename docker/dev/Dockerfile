# Development image: a Linux toolchain and runtime for the repository, which is bind-mounted rather than copied.
FROM eclipse-temurin:25-jdk

ENV DEBIAN_FRONTEND=noninteractive

# Unreal Tournament and uthooks are 32-bit, so the game's runtime libraries are the i386 variants. The bundled ffmpeg
# is 64-bit and links libxcb, libasound and libpulse without shipping them.
RUN dpkg --add-architecture i386 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        rsync \
        gcc \
        gcc-multilib \
        libc6-dev-i386 \
        xvfb \
        x11-utils \
        mesa-utils \
        libxcb1 \
        libxcb-shm0 \
        libxcb-shape0 \
        libxcb-xfixes0 \
        libpulse0 \
        libc6:i386 \
        libstdc++6:i386 \
        libgcc-s1:i386 \
        libgl1:i386 \
        libglx-mesa0:i386 \
        libgl1-mesa-dri:i386 \
        libglu1-mesa:i386 \
        libsdl2-2.0-0:i386 \
        libopenal1:i386 \
        libpulse0:i386 \
        libx11-6:i386 \
        libxext6:i386 \
        libxrandr2:i386 \
        libxcursor1:i386 \
        libxi6:i386 \
        libxfixes3:i386 \
        libxss1:i386 \
    && (apt-get install -y --no-install-recommends libasound2t64 libasound2t64:i386 \
        || apt-get install -y --no-install-recommends libasound2 libasound2:i386) \
    && rm -rf /var/lib/apt/lists/*

# A newer Mesa than the distribution ships, for both architectures. Build with --build-arg MESA_PPA= to skip it.
ARG MESA_PPA=kisak/kisak-mesa
ARG MESA_PPA_FINGERPRINT=EB8B81E14DA65431D7504EA8F63F0F2B90935439
RUN if [ -n "$MESA_PPA" ]; then \
        set -eu; \
        . /etc/os-release; \
        apt-get update; \
        apt-get install -y --no-install-recommends curl gnupg; \
        mkdir -p /etc/apt/keyrings; \
        curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x${MESA_PPA_FINGERPRINT}" -o /tmp/mesa-ppa.asc; \
        gpg --batch --show-keys --with-colons /tmp/mesa-ppa.asc \
            | grep -q "^fpr:::::::::${MESA_PPA_FINGERPRINT}:"; \
        gpg --batch --dearmor -o /etc/apt/keyrings/mesa-ppa.gpg /tmp/mesa-ppa.asc; \
        rm /tmp/mesa-ppa.asc; \
        printf 'Types: deb\nURIs: https://ppa.launchpadcontent.net/%s/ubuntu\nSuites: %s\nComponents: main\nSigned-By: /etc/apt/keyrings/mesa-ppa.gpg\n' \
            "$MESA_PPA" "$VERSION_CODENAME" > /etc/apt/sources.list.d/mesa-ppa.sources; \
        apt-get update; \
        apt-get install -y --no-install-recommends \
            libgl1-mesa-dri libglx-mesa0 libglu1-mesa mesa-utils \
            libgl1-mesa-dri:i386 libglx-mesa0:i386 libglu1-mesa:i386; \
        apt-get purge -y curl gnupg; \
        apt-get autoremove -y; \
        rm -rf /var/lib/apt/lists/*; \
    fi

ENV DISPLAY=:99 \
    SCREEN_SIZE=1920x1080 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    GRADLE_OPTS=-Dorg.gradle.daemon=false \
    UTRECORDER_HOME=/var/lib/utrecorder

COPY entrypoint.sh /usr/local/bin/entrypoint
COPY utrecorder-cli.sh /usr/local/bin/utrecorder-cli
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint /usr/local/bin/utrecorder-cli \
    && chmod +x /usr/local/bin/entrypoint /usr/local/bin/utrecorder-cli

WORKDIR /utrecorder

ENTRYPOINT ["entrypoint"]
CMD ["bash"]
