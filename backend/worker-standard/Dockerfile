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

ARG TARGETARCH
ARG KUBECTL_VERSION=v1.37.1
ARG KUBECTL_SHA256_AMD64=65691ff77eb6fa44c908b77a1082c9f092c3b9733b5cefabec0d1104890e21a8
ARG KUBECTL_SHA256_ARM64=ff749f4b78d9c4f1ec87307df9b50119ed819e2094aa9810cb9acffc3286c8c7

RUN apk add --no-cache mariadb-client nfs-utils postgresql18-client su-exec util-linux \
    && case "${TARGETARCH}" in \
        amd64) KUBECTL_SHA256="${KUBECTL_SHA256_AMD64}" ;; \
        arm64) KUBECTL_SHA256="${KUBECTL_SHA256_ARM64}" ;; \
        *) echo "Unsupported kubectl architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && wget -q -O /usr/local/bin/kubectl "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" \
    && echo "${KUBECTL_SHA256}  /usr/local/bin/kubectl" | sha256sum -c - \
    && chmod 0755 /usr/local/bin/kubectl \
    && kubectl version --client | grep -F "Client Version: ${KUBECTL_VERSION}" \
    && addgroup -S application \
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
    && mkdir -p /application/artifacts \
    && mkdir -p /run/alertify-nfs-helper /run/alertify-nfs-mounts \
    && chown application:application /application/compiled-alerts /application/artifacts \
    && chmod 0711 /run/alertify-nfs-helper /run/alertify-nfs-mounts

EXPOSE 9090

ENTRYPOINT ["/application/start-worker-standard.sh"]
