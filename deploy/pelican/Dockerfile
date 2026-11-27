# syntax=docker/dockerfile:1
# Build on the builder CPU; the JVM distribution is architecture-independent.
FROM --platform=$BUILDPLATFORM node:24-bookworm-slim AS build-node
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS build
COPY --from=build-node /usr/local/ /usr/local/
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /source
COPY . .
RUN chmod +x gradlew && CERBERUS_TEST_FFMPEG=/usr/bin/ffmpeg CERBERUS_TEST_FFPROBE=/usr/bin/ffprobe CERBERUS_TEST_NODE=/usr/local/bin/node ./gradlew \
    build :examples:welcome-plugin:build :examples:music-plugin:build \
    :examples:server-setup-plugin:build installDist --no-daemon --no-watch-fs --console=plain

FROM node:24-bookworm-slim AS runtime-node
FROM eclipse-temurin:21-jre-noble AS runtime
LABEL org.opencontainers.image.title="Cerberus TS for Pelican" \
      org.opencontainers.image.vendor="Cerberus Labs" \
      org.opencontainers.image.source="https://github.com/Cerberus-Labs-Technologies/Cerberus-TS-Framework" \
      org.opencontainers.image.licenses="MIT"
ARG YT_DLP_VERSION=2026.11.23
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash ca-certificates ffmpeg fontconfig fonts-dejavu-core libstdc++6 python3 python3-venv tini \
    && python3 -m venv /opt/yt-dlp \
    && /opt/yt-dlp/bin/pip install --no-cache-dir "yt-dlp[default]==${YT_DLP_VERSION}" \
    && /opt/yt-dlp/bin/pip check \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --home-dir /home/container --shell /bin/bash container
COPY --from=runtime-node /usr/local/bin/node /usr/local/bin/node
COPY --from=build /source/build/install/cerberus-ts /opt/cerberus/app
COPY --from=build /source/examples/welcome-plugin/build/libs/welcome-plugin.jar /opt/cerberus/bundled/welcome-plugin.jar
COPY --from=build /source/examples/music-plugin/build/libs/music-plugin.jar /opt/cerberus/bundled/music-plugin.jar
COPY --from=build /source/examples/server-setup-plugin/build/libs/server-setup-plugin.jar /opt/cerberus/bundled/server-setup-plugin.jar
COPY deploy/pelican/start.sh /opt/cerberus/start.sh
ENV PATH="/opt/yt-dlp/bin:${PATH}" USER=container \
    CERBERUS_MUSIC_FFMPEG=/usr/bin/ffmpeg CERBERUS_MUSIC_YTDLP=/opt/yt-dlp/bin/yt-dlp \
    CERBERUS_MUSIC_JS_RUNTIME=node
RUN chmod 0755 /opt/cerberus/start.sh \
    && java -version && node --version && ffmpeg -version && yt-dlp --version \
    && /opt/yt-dlp/bin/python -c "import yt_dlp, yt_dlp_ejs" \
    && yt-dlp --ignore-config --js-runtimes "$CERBERUS_MUSIC_JS_RUNTIME" --list-extractors >/dev/null \
    && /opt/cerberus/app/bin/cerberus-ts --help
USER container
WORKDIR /home/container
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/bin/tini", "-g", "--"]
CMD ["/bin/bash", "/opt/cerberus/start.sh"]
