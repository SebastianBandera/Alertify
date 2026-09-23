# syntax=docker/dockerfile:1

ARG BACKEND_BUILD_IMAGE
ARG WORKER_PLAYWRIGHT_JAVA_IMAGE
ARG WORKER_PLAYWRIGHT_RUNTIME_IMAGE

FROM ${BACKEND_BUILD_IMAGE} AS builder

RUN if command -v apk >/dev/null 2>&1; then apk add --no-cache gcompat; fi

WORKDIR /build

COPY . .
RUN --mount=type=cache,id=alertify-maven,target=/root/.m2,sharing=locked \
    mvn --batch-mode --no-transfer-progress -pl worker-playwright -am install -DskipTests
RUN --mount=type=cache,id=alertify-maven,target=/root/.m2,sharing=locked \
    mvn --batch-mode --no-transfer-progress -pl worker-playwright \
        dependency:copy-dependencies -DincludeScope=runtime \
        -DoutputDirectory=/build/compiler-classpath

FROM ${WORKER_PLAYWRIGHT_JAVA_IMAGE} AS java-runtime

FROM ${WORKER_PLAYWRIGHT_RUNTIME_IMAGE}

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk

RUN groupadd --system application \
    && useradd --system --gid application --create-home --home-dir /home/application application

WORKDIR /application

COPY --from=builder --chown=application:application \
    /build/worker-playwright/target/alertify-worker-playwright.jar \
    /application/alertify-worker-playwright.jar

COPY --from=builder --chown=application:application \
    /build/compiler-classpath \
    /application/compiler-classpath

COPY --chown=application:application start-worker-playwright.sh /application/start-worker-playwright.sh

RUN chmod 0755 /application/start-worker-playwright.sh \
    && mkdir -p /application/compiled-alerts \
    && mkdir -p /application/artifacts \
    && chown application:application /application/compiled-alerts /application/artifacts

USER application

EXPOSE 9090

ENTRYPOINT ["/application/start-worker-playwright.sh"]
