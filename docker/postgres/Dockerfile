# ghcr.io/hephaestus-build/postgres — official PostgreSQL (Debian) + pg_partman, used in every
# environment (Alpine has no partman package). No shared_preload_libraries: maintenance runs as plain
# SQL from the app scheduler, so this stays stock Postgres + one extension. See ADR 0018 and ADR 0038.
FROM postgres:18-bookworm@sha256:1c59e2c3c818eaa0f0628f695b36e7c9e362d6b219b36a54a32df645cbd7e1af AS os-packages
ARG PARTMAN_VERSION=5.5.0-1.pgdg12+1
# CI always refreshes this stage with --no-cache-filter os-packages; see vulnerability-remediation.mdx.
RUN set -eux; \
    apt-get update -o APT::Update::Error-Mode=any; \
    apt-get upgrade -y; \
    apt-get install -y --no-install-recommends "postgresql-18-partman=${PARTMAN_VERSION}" pgbackrest; \
    rm -rf /var/lib/apt/lists/*

FROM os-packages AS runtime

# Replace upstream's Go-based gosu with distro-patched setpriv, preserving the entrypoint contract.
# Delete the original in a separate layer so image scanners observe its removal before replacement.
RUN rm /usr/local/bin/gosu
COPY gosu-setpriv.sh /usr/local/bin/gosu
RUN set -eux; \
    chmod 0755 /usr/local/bin/gosu; \
    expected="$(id -u postgres):$(id -g postgres):$(id -G postgres)"; \
    actual="$(gosu postgres sh -c 'echo "$(id -u):$(id -g):$(id -G)"')"; \
    [ "${expected}" = "${actual}" ]
