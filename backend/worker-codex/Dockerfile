# syntax=docker/dockerfile:1

ARG BACKEND_BUILD_IMAGE
ARG WORKER_RUNTIME_IMAGE

FROM ${BACKEND_BUILD_IMAGE} AS builder

RUN if command -v apk >/dev/null 2>&1; then apk add --no-cache gcompat; fi

WORKDIR /build

COPY . .

RUN --mount=type=cache,id=alertify-maven,target=/root/.m2,sharing=locked \
    mvn --batch-mode --no-transfer-progress -pl worker-codex -am package -DskipTests

FROM ${WORKER_RUNTIME_IMAGE}

RUN addgroup -S application && adduser -S -G application application \
    && mkdir -p /application/data && chown -R application:application /application

WORKDIR /application

COPY --from=builder --chown=application:application /build/worker-codex/target/alertify-worker-codex.jar /application/alertify-worker-codex.jar

USER application
EXPOSE 9090

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=80.0", "-jar", "/application/alertify-worker-codex.jar"]
