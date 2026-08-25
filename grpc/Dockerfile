ARG GRPC_OPENSSL_IMAGE

FROM ${GRPC_OPENSSL_IMAGE}

COPY generate-certificates.sh /usr/local/bin/generate-grpc-certificates

# Keep the entrypoint executable even when the project was extracted or edited
# on Windows and its line endings were converted to CRLF.
RUN sed -i 's/\r$//' /usr/local/bin/generate-grpc-certificates \
    && chmod 0755 /usr/local/bin/generate-grpc-certificates

ENTRYPOINT ["/usr/local/bin/generate-grpc-certificates"]
CMD ["rotate"]
