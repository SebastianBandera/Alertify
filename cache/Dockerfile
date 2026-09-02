ARG REDIS_IMAGE

FROM ${REDIS_IMAGE}

COPY --chmod=0755 start-cache.sh /usr/local/bin/start-cache.sh

ENTRYPOINT ["/usr/local/bin/start-cache.sh"]
