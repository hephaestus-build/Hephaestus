# syntax=docker/dockerfile:1.27@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e
# renovate: datasource=node-version depName=node
ARG NODE_VERSION=24.20.0
# renovate: datasource=github-releases depName=gitleaks/gitleaks
ARG GITLEAKS_VERSION=v8.30.1
# The commit the tag pointed at when it was pinned; a tag can be moved, a commit cannot.
ARG GITLEAKS_COMMIT=83d9cd684c87d95d656c1458ef04895a7f1cbd8e

# Gitleaks is compiled from its pinned release rather than copied from the upstream image: the
# published binary carries the Go toolchain and x/ modules of its release date, and this image is
# scanned as a release artifact. The build pins the toolchain by digest and every module by version.
FROM golang:1.27.1-alpine@sha256:cf6fca6641884b8433441b2b0652976f975e1d0fdd26d177eaaf8596087f3125 AS gitleaks
ARG GITLEAKS_VERSION
ARG GITLEAKS_COMMIT
# The pinned image's Go is the one that builds; go.mod may not pull a newer toolchain.
ENV GOTOOLCHAIN=local
RUN apk add --no-cache git
WORKDIR /src
RUN git clone --depth 1 --branch "${GITLEAKS_VERSION}" https://github.com/gitleaks/gitleaks.git . && \
    git checkout --detach "${GITLEAKS_COMMIT}" && \
    test "$(git rev-parse HEAD)" = "${GITLEAKS_COMMIT}" && \
    go get golang.org/x/crypto@v0.57.0 golang.org/x/text@v0.42.0 && \
    go mod tidy && \
    CGO_ENABLED=0 go build -trimpath \
        -ldflags="-s -w -X github.com/zricethezav/gitleaks/v8/version.Version=${GITLEAKS_VERSION}" \
        -o /usr/bin/gitleaks . && \
    /usr/bin/gitleaks version

FROM node:${NODE_VERSION}-slim@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e
ARG SOURCE_COMMIT
RUN echo "source commit: ${SOURCE_COMMIT}" && apt-get update -qq && apt-get upgrade -y -qq && \
    apt-get install -y --no-install-recommends git ca-certificates util-linux && \
    rm -rf /var/lib/apt/lists/* && mkdir -p /git /snapshot /opt/git && touch /git/lock && chown 1000:1000 /git /git/lock /snapshot
# The runner is plain TypeScript on Node: npm, corepack and Yarn leave; apt and dpkg stay.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* \
        /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
        /usr/local/bin/yarn /usr/local/bin/yarnpkg
COPY --from=gitleaks /usr/bin/gitleaks /usr/bin/gitleaks
COPY git/operation.ts git/lines.ts git/process.ts git/review-diff.ts git/review-commits.ts git/secret-scan.ts /opt/git/
LABEL hephaestus.component="git-preparation"
USER 1000:1000
WORKDIR /git
CMD ["node", "/opt/git/operation.ts"]
