# syntax=docker/dockerfile:1

ARG BACKEND_BUILD_IMAGE
ARG BACKEND_RUNTIME_IMAGE

FROM ${BACKEND_BUILD_IMAGE} AS builder

WORKDIR /build

COPY . .
RUN --mount=type=cache,id=alertify-maven,target=/root/.m2,sharing=locked \
    mvn --batch-mode --no-transfer-progress -pl worker-playwright -am package -DskipTests
RUN cp -R worker-playwright/src /runtime-src

FROM ${BACKEND_RUNTIME_IMAGE}

RUN addgroup -S application \
    && adduser -S -G application application

WORKDIR /application

COPY --from=builder --chown=application:application \
    /build/worker-playwright/target/alertify-worker-playwright.jar \
    /application/alertify-worker-playwright.jar

COPY --from=builder --chown=application:application \
    /runtime-src \
    /application/src

COPY --chown=application:application start-worker-playwright.sh /application/start-worker-playwright.sh

RUN chmod 0755 /application/start-worker-playwright.sh

USER application

EXPOSE 9090

ENTRYPOINT ["/application/start-worker-playwright.sh"]
