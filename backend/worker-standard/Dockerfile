# syntax=docker/dockerfile:1

ARG BACKEND_BUILD_IMAGE
ARG WORKER_RUNTIME_IMAGE

FROM ${BACKEND_BUILD_IMAGE} AS builder

RUN if command -v apk >/dev/null 2>&1; then apk add --no-cache gcompat; fi

WORKDIR /build

COPY . .
RUN --mount=type=cache,id=alertify-maven,target=/root/.m2,sharing=locked \
    mvn --batch-mode --no-transfer-progress -pl worker-standard -am install -DskipTests
RUN --mount=type=cache,id=alertify-maven,target=/root/.m2,sharing=locked \
    mvn --batch-mode --no-transfer-progress -pl worker-standard \
        dependency:copy-dependencies -DincludeScope=runtime \
        -DoutputDirectory=/build/compiler-classpath

FROM ${WORKER_RUNTIME_IMAGE}

RUN addgroup -S application \
    && adduser -S -G application application

WORKDIR /application

COPY --from=builder --chown=application:application \
    /build/worker-standard/target/alertify-worker-standard.jar \
    /application/alertify-worker-standard.jar

COPY --from=builder --chown=application:application \
    /build/compiler-classpath \
    /application/compiler-classpath

COPY --chown=application:application start-worker-standard.sh /application/start-worker-standard.sh

RUN chmod 0755 /application/start-worker-standard.sh \
    && mkdir -p /application/compiled-alerts \
    && chown application:application /application/compiled-alerts

USER application

EXPOSE 9090

ENTRYPOINT ["/application/start-worker-standard.sh"]
