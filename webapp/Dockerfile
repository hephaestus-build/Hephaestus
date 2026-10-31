# syntax=docker/dockerfile:1.27@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e
FROM ghcr.io/pnpm/pnpm:12.3.4@sha256:b81d53184f670fe19d1a33f9d5041907d314b31d596838e8133cbd83d45be043 AS build
WORKDIR /repo
# renovate: datasource=node-version depName=node
ARG NODE_VERSION=24.22.1
RUN pnpm runtime set node ${NODE_VERSION} -g
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json .oxfmtrc.json .oxlintrc.json oxlint.react.jsonc oxlint.app.jsonc /repo/
COPY webapp/package.json /repo/webapp/
COPY docs/package.json /repo/docs/
COPY extension/package.json /repo/extension/
COPY scripts/api-generator/package.json /repo/scripts/api-generator/
COPY patches/ /repo/patches/
COPY docker/agents/pi/patches/ /repo/docker/agents/pi/patches/
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store,sharing=locked \
    pnpm fetch --filter webapp
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store,sharing=locked \
    pnpm install --offline --frozen-lockfile --ignore-scripts --filter webapp
# `pnpm run` installs before it runs, so the workspace root's `prepare` executes here even though
# the install above ignored scripts. It has to resolve; there is no work tree, so it enables nothing.
COPY scripts/ /repo/scripts/
COPY webapp/ /repo/webapp/
# The prose rule reads the shared words, enforced paths, and product vocabulary at lint time.
COPY .vale/ /repo/.vale/
COPY docs/contributor/practice-feedback-language.md /repo/docs/contributor/practice-feedback-language.md
COPY server/application/src/main/resources/practices/default-catalog.json /repo/server/application/src/main/resources/practices/default-catalog.json
# The build emits no source maps; the image fails rather than ship one that appears later.
RUN pnpm --filter webapp run build && \
    ! find webapp/dist -name '*.map' -print -quit | grep -q .

FROM nginx:1.30.6-alpine@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951 AS os-packages
# nginx's upstream package repository is not persisted, so this upgrades distribution packages only.
RUN apk upgrade --no-cache && apk add --no-cache bash sed grep curl

FROM os-packages AS runtime
ARG SOURCE_BRANCH
ARG SOURCE_COMMIT
ENV BUILD_GIT_BRANCH=$SOURCE_BRANCH BUILD_GIT_COMMIT=$SOURCE_COMMIT

COPY --from=build /repo/webapp/dist /usr/share/nginx/html
COPY webapp/docker/entrypoint.sh /entrypoint.sh
COPY webapp/docker/nginx-globals.conf /etc/nginx/hephaestus/globals.conf
COPY webapp/docker/nginx-http.conf /etc/nginx/hephaestus/http.conf
COPY webapp/docker/nginx-main.conf /etc/nginx/nginx.conf
COPY webapp/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY webapp/docker/security-headers.conf /etc/nginx/security-headers.conf
# The maintenance page mode, which docker/compose.proxy.yaml runs with `nginx -c`.
COPY webapp/docker/nginx-maintenance.conf /etc/nginx/hephaestus/maintenance.conf
COPY webapp/docker/maintenance.html /usr/share/nginx/maintenance/index.html
COPY webapp/src/environment/index.ts /app/src/environment/index.ts
RUN chmod +x /entrypoint.sh
# The base image's nginx user. Numeric, so Kubernetes can verify runAsNonRoot. Root owns every
# file above; this user only reads them and writes to /tmp/hephaestus-webapp, which every service
# that runs the image mounts as a writable directory.
USER 101:101
EXPOSE 8080
ENTRYPOINT ["/bin/bash", "-c", "/entrypoint.sh && exec nginx -g 'daemon off;'"]
