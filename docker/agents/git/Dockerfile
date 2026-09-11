# syntax=docker/dockerfile:1.27@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e
# renovate: datasource=node-version depName=node
ARG NODE_VERSION=24.20.0
FROM ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f AS gitleaks
FROM node:${NODE_VERSION}-slim@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e
ARG SOURCE_COMMIT
RUN echo "source commit: ${SOURCE_COMMIT}" && apt-get update -qq && apt-get upgrade -y -qq && \
    apt-get install -y --no-install-recommends git ca-certificates util-linux && \
    rm -rf /var/lib/apt/lists/* && mkdir -p /git /snapshot /opt/git && touch /git/lock && chown 1000:1000 /git /git/lock /snapshot
COPY --from=gitleaks /usr/bin/gitleaks /usr/bin/gitleaks
COPY git/operation.ts git/review-diff.ts git/review-commits.ts git/secret-scan.ts /opt/git/
LABEL hephaestus.component="git-preparation"
USER 1000:1000
WORKDIR /git
CMD ["node", "/opt/git/operation.ts"]
