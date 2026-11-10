# syntax=docker/dockerfile:1.28@sha256:bb22d9815c728170f72750f4e5b0d672e06176142e1d602c7e66c050100b7e5b
FROM ghcr.io/pnpm/pnpm:12.18.0@sha256:41c39d1c734b0a810669c07587888aa7dbe9ef9b4c8e98b346b3654f7b269b6d AS build
WORKDIR /repo
# renovate: datasource=node-version depName=node
ARG NODE_VERSION=24.22.2
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
COPY webapp/src/environment/index.ts /app/src/environment/index.ts
RUN chmod +x /entrypoint.sh
# The base image's nginx user. Numeric, so Kubernetes can verify runAsNonRoot. Root owns every
# file above; this user only reads them and writes to /tmp/hephaestus-webapp, which every service
# that runs the image mounts as a writable directory.
USER 101:101
EXPOSE 8080
ENTRYPOINT ["/bin/bash", "-c", "/entrypoint.sh && exec nginx -g 'daemon off;'"]
