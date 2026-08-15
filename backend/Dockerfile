ARG BACKEND_BUILD_IMAGE
ARG BACKEND_RUNTIME_IMAGE

FROM ${BACKEND_BUILD_IMAGE} AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package -DskipTests

FROM ${BACKEND_RUNTIME_IMAGE}

RUN addgroup -S application \
    && adduser -S -G application application

WORKDIR /application

COPY --from=builder --chown=application:application \
    /build/target/alertify-backend.jar \
    /application/alertify-backend.jar

COPY --chown=application:application start-backend.sh /application/start-backend.sh

RUN chmod 0755 /application/start-backend.sh

USER application

EXPOSE 8080
EXPOSE 5005

ENTRYPOINT ["/application/start-backend.sh"]
