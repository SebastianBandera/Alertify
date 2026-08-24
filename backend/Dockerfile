ARG BACKEND_BUILD_IMAGE
ARG BACKEND_RUNTIME_IMAGE

FROM ${BACKEND_BUILD_IMAGE} AS builder

WORKDIR /build

COPY . .
RUN mvn --batch-mode --no-transfer-progress -pl core -am package -DskipTests
RUN cp -R core/src /runtime-src \
    && rm -f \
        /runtime-src/main/java/app/alertify/services/secret/key/PrivateKeyPart.java

FROM ${BACKEND_RUNTIME_IMAGE}

RUN addgroup -S application \
    && adduser -S -G application application

WORKDIR /application

COPY --from=builder --chown=application:application \
    /build/core/target/alertify-backend.jar \
    /application/alertify-backend.jar

COPY --from=builder --chown=application:application \
    /runtime-src \
    /application/src

COPY --chown=application:application start-backend.sh /application/start-backend.sh

RUN chmod 0755 /application/start-backend.sh

USER application

EXPOSE 8080
EXPOSE 5005

ENTRYPOINT ["/application/start-backend.sh"]
