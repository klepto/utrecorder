# syntax=docker/dockerfile:1
# Production images: builds the server and the CLI distributions from source, then runs one of them with the 32-bit
# runtime Unreal Tournament needs. The server is the last stage, so a build without --target builds the server.

FROM eclipse-temurin:25-jdk AS builder

ENV DEBIAN_FRONTEND=noninteractive

# uthooks.so is compiled for Linux here, and UTRecorderConsole compiles against a materialized UT99 install, which
# runs the game's own Linux binaries under a virtual display, so the builder needs the same 32-bit runtime the final
# image does, plus the C toolchain. The tests load the bundled ffmpeg's 64-bit libraries, which link libxcb, libasound
# and libpulse without shipping them, so the builder carries those too.
RUN dpkg --add-architecture i386 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        gcc \
        gcc-multilib \
        libc6-dev-i386 \
        xvfb \
        x11-utils \
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
    && (apt-get install -y --no-install-recommends libasound2t64:i386 libasound2t64 \
        || apt-get install -y --no-install-recommends libasound2:i386 libasound2) \
    && rm -rf /var/lib/apt/lists/*

ENV DISPLAY=:99 \
    SCREEN_SIZE=1920x1080 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    GRADLE_OPTS=-Dorg.gradle.daemon=false

WORKDIR /utrecorder
COPY . .

# The version the distributions carry. Empty keeps version= from gradle.properties. release.yml sets it for a
# development build, whose version is never committed.
ARG VERSION=

# The Gradle user home is a build cache mount, so a rebuild reuses the Gradle distribution and the dependencies it
# downloaded before instead of downloading them again. The development home's cache/ is a build cache mount too, so
# the UT99 install UCC compiles against is extracted from the iso and patch downloaded before. The step runs the tests
# as well as the build: `check` comes first, so an image exists only when the tests pass.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/utrecorder/.utrecorder/cache \
    Xvfb "$DISPLAY" -screen 0 "${SCREEN_SIZE}x24" -nolisten tcp -ac >/dev/null 2>&1 & \
    for _ in $(seq 1 100); do \
        xdpyinfo -display "$DISPLAY" >/dev/null 2>&1 && break; \
        sleep 0.1; \
    done \
    && xdpyinfo -display "$DISPLAY" >/dev/null 2>&1 \
    && { ./gradlew --no-daemon ${VERSION:+-Pversion=$VERSION} check :utrecorder-server:installDist :utrecorder-cli:installDist \
        || { cat utrecorder-hooks/build/native-logs/*/compile.log 2>/dev/null; exit 1; }; }

FROM eclipse-temurin:25-jre AS runtime

ENV DEBIAN_FRONTEND=noninteractive

# The recorder launches Unreal Tournament's Linux port to capture footage; both it and the bundled ffmpeg are 32-bit
# except ffmpeg's own binary, which is 64-bit and links libxcb, libasound and libpulse without shipping them.
RUN dpkg --add-architecture i386 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        xvfb \
        x11-utils \
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
            libgl1-mesa-dri libglx-mesa0 libglu1-mesa \
            libgl1-mesa-dri:i386 libglx-mesa0:i386 libglu1-mesa:i386; \
        apt-get purge -y curl gnupg; \
        apt-get autoremove -y; \
        rm -rf /var/lib/apt/lists/*; \
    fi

ENV DISPLAY=:99 \
    SCREEN_SIZE=1920x1080 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    UTRECORDER_HOME=/var/lib/utrecorder \
    UTRECORDER_CACHE=/var/cache/utrecorder \
    MESA_SHADER_CACHE_DIR=/var/cache/utrecorder/mesa

# Game packages no redirect serves, maintained by hand in the repository. The home is a volume that starts empty, so
# the packages directory is moved out of it into the image.
COPY .utrecorder/packages /opt/utrecorder/packages
ENV UTRECORDER_PACKAGES=/opt/utrecorder/packages

COPY docker/entrypoint.sh /usr/local/bin/entrypoint
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint && chmod +x /usr/local/bin/entrypoint

# The recorder, Xvfb and the game run as an unprivileged user with a fixed id, so a volume keeps its owner across
# rebuilds. Both volume paths are created owned by that user before they are declared, and Docker copies that owner into
# a new named volume. Xvfb without root cannot create its socket directory as root expects, so it exists already.
RUN groupadd --gid 10001 utrecorder \
    && useradd --uid 10001 --gid utrecorder --create-home --shell /usr/sbin/nologin utrecorder \
    && install -d -o utrecorder -g utrecorder /var/lib/utrecorder /var/cache/utrecorder \
    && install -d -m 1777 /tmp/.X11-unix

# The cache is a volume of its own, outside the home, so that containers sharing nothing else can share downloads and
# Mesa's shader cache, which MESA_SHADER_CACHE_DIR above puts in its mesa/ directory. Mesa creates that directory and
# bounds its size itself.
VOLUME /var/lib/utrecorder /var/cache/utrecorder

USER utrecorder

ENTRYPOINT ["entrypoint"]

FROM runtime AS cli

# The builder leaves the jars of a distribution readable by root alone, so each is copied readable by the runtime user.
COPY --from=builder --chmod=a+rX /utrecorder/utrecorder-cli/build/install/utrecorder-cli /opt/utrecorder-cli

ENTRYPOINT ["entrypoint", "/opt/utrecorder-cli/bin/utrecorder-cli"]

FROM runtime AS server

COPY --from=builder --chmod=a+rX /utrecorder/utrecorder-server/build/install/utrecorder-server /opt/utrecorder-server

EXPOSE 8080

CMD ["/opt/utrecorder-server/bin/utrecorder-server"]
