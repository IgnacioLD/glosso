# Build stage: compiles the Ktor server and produces a runnable distribution.
# Android is disabled: this image has no Android SDK, so AGP must not be applied.
FROM gradle:9.7.1-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle :server:installDist --no-daemon --console=plain -Pglosso.android=false

# Runtime stage.
FROM eclipse-temurin:21-jre
# La imagen base se publica cada cierto tiempo y entre medias salen parches de
# seguridad de Ubuntu (OpenSSL, CVE-2026-84782). Se aplican al construir, para
# que el escaner de CI no falle por algo ya corregido en el repositorio de
# paquetes y la imagen desplegada no lleve librerias con fallos conocidos.
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/server/build/install/server/ ./
# El proceso no corre como root. UID/GID numericos fijos (sin crear usuario: el
# JRE no necesita entrada en /etc/passwd) para poder alinearlos con el volumen de
# assets y con los tmpfs en docker-compose, y para que el chown no dependa de
# useradd en la imagen base.
RUN chown -R 10001:10001 /app
USER 10001:10001
# HOME y TMPDIR escribibles: el runtime (ONNX Runtime y SQLite extraen su
# libreria nativa con System.load, que exige un directorio escribible y ejecutable)
# usa java.io.tmpdir, que en el compose es un tmpfs con exec.
ENV HOME=/tmp
ENV TMPDIR=/tmp
ENV PORT=8080
ENV GLOSSO_ASSETS_DIR=/assets
EXPOSE 8080
ENTRYPOINT ["./bin/server"]
