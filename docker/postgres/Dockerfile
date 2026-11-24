# ghcr.io/hephaestus-build/postgres — official PostgreSQL (Debian) + pg_partman, used in every
# environment (Alpine has no partman package). pg_partman maintenance runs as plain SQL from the app
# scheduler, so its background worker is not preloaded (ADR 0018, ADR 0038). size-from-memory.sh
# preloads pg_stat_statements and sizes the memory settings. tini is PID 1, so postgres is not.
FROM postgres:18-bookworm@sha256:1c59e2c3c818eaa0f0628f695b36e7c9e362d6b219b36a54a32df645cbd7e1af AS os-packages
ARG PARTMAN_VERSION=5.5.0-1.pgdg12+1
RUN set -eux; \
    apt-get update -o APT::Update::Error-Mode=any; \
    apt-get upgrade -y; \
    apt-get install -y --no-install-recommends "postgresql-18-partman=${PARTMAN_VERSION}" pgbackrest tini; \
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

COPY size-from-memory.sh /usr/local/bin/size-from-memory.sh
# PostgreSQL 18's postmaster treats every child it did not start as a crashed backend and restarts
# all of them. As PID 1 it inherits the orphans of exec sessions, such as a timed-out exec probe's
# pg_isready, so tini takes PID 1 and reaps them. The entrypoint chain execs, so postgres stays
# tini's child: a lock file left by a postmaster that ran as PID 1 then names the parent, which
# PostgreSQL accepts as stale. A pod-level shareProcessNamespace does not: PID 1 is then the pause
# process, which PostgreSQL takes for a live postmaster.
ENTRYPOINT ["tini", "--", "size-from-memory.sh"]
# Docker drops the base image's CMD when an image sets ENTRYPOINT.
CMD ["postgres"]
