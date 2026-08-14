ARG DOCKER_CLI_IMAGE=docker:28-cli

FROM ${DOCKER_CLI_IMAGE}

# The host only needs Docker. Node.js runs inside this helper image.
RUN apk add --no-cache nodejs \
    && node --version \
    && docker compose version

WORKDIR /workspace

ENTRYPOINT ["node", "/workspace/run.js"]
CMD []
