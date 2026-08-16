ARG KEYCLOAK_IMAGE

FROM ${KEYCLOAK_IMAGE} AS builder

# These are non-secret build-time options persisted in the optimized image.
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_HTTP_RELATIVE_PATH=/identity

RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK_IMAGE}

COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 --chmod=0755 start-identity.sh /opt/keycloak/config/start-identity.sh
COPY --chown=1000:0 --chmod=0755 configure-permanent-admin.sh /opt/keycloak/config/configure-permanent-admin.sh
COPY --chown=1000:0 --chmod=0644 realm-template.json /opt/keycloak/config/realm-template.json

ENTRYPOINT ["/bin/bash", "/opt/keycloak/config/start-identity.sh"]
CMD ["start", "--optimized", "--import-realm"]
