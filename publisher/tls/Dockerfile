ARG PUBLISHER_TLS_OPENSSL_IMAGE

FROM ${PUBLISHER_TLS_OPENSSL_IMAGE}

COPY generate-certificate.sh /usr/local/bin/generate-publisher-certificate

RUN sed -i 's/\r$//' /usr/local/bin/generate-publisher-certificate \
    && chmod 0755 /usr/local/bin/generate-publisher-certificate

ENTRYPOINT ["/usr/local/bin/generate-publisher-certificate"]
CMD ["rotate"]
