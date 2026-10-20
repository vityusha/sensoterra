# Сборка: Gradle wrapper внутри контейнера, тесты гоняет CI (./gradlew build), здесь только дистрибутив.
# Тег закреплён: в 21.0.12.1 (21-jdk на 2026-10) битый CDS-архив — JVM падает на Stream.collect.
FROM eclipse-temurin:21.0.8_9-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21.0.8_9-jre
# Paho пишет файловое хранилище сообщений в рабочий каталог — он должен быть доступен на запись.
RUN useradd --system --create-home --home-dir /app sensoterra
WORKDIR /app
COPY --from=build --chown=sensoterra:sensoterra /src/build/install/sensoterra-server ./
USER sensoterra
EXPOSE 8080
ENTRYPOINT ["/app/bin/sensoterra-server"]
