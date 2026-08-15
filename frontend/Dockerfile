ARG FRONTEND_BUILD_IMAGE
ARG FRONTEND_RUNTIME_IMAGE

FROM ${FRONTEND_BUILD_IMAGE} AS builder

WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY angular.json tsconfig.json tsconfig.app.json ./
COPY public ./public
COPY src ./src

RUN npm run build

FROM ${FRONTEND_RUNTIME_IMAGE}

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist/alertify-frontend/browser /usr/share/nginx/html
COPY runtime-config.template.json /opt/alertify/runtime-config.template.json
COPY start-frontend.sh /usr/local/bin/start-frontend.sh

RUN chmod 0755 /usr/local/bin/start-frontend.sh \
    && mkdir -p /usr/share/nginx/html/config

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/start-frontend.sh"]
CMD ["nginx", "-g", "daemon off;"]
