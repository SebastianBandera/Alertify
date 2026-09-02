ARG PUBLISHER_BASE_IMAGE

FROM ${PUBLISHER_BASE_IMAGE}

COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=0755 19-application-context.envsh /docker-entrypoint.d/19-application-context.envsh
