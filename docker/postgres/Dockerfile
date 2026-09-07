# ghcr.io/hephaestus-build/postgres — official PostgreSQL (Debian) + pg_partman, used in every
# environment (Alpine has no partman package). No shared_preload_libraries: maintenance runs as plain
# SQL from the app scheduler, so this stays stock Postgres + one extension. See ADR 0018 and ADR 0038.
FROM postgres:18-bookworm@sha256:1c59e2c3c818eaa0f0628f695b36e7c9e362d6b219b36a54a32df645cbd7e1af
# Pinned for reproducibility (PGDG bookworm pool). Bump deliberately.
ARG PARTMAN_VERSION=5.5.0-1.pgdg12+1
# The tag floats, so docker-library rebuilds already carry most OS updates here; the upgrade closes
# the window between a Debian security publication and the next upstream rebuild. Same posture as
# webapp/Dockerfile and docker/agents/pi/Dockerfile so no image is the one that blocks a release.
#
# SOURCE_COMMIT is what makes that upgrade actually run: the builder imports the `cache-main`
# registry cache, and a layer with no per-build input would replay a stale package set instead.
ARG SOURCE_COMMIT
RUN set -eux; \
    echo "source commit: ${SOURCE_COMMIT:-<unset — local build>}"; \
    apt-get update; \
    apt-get upgrade -y; \
    apt-get install -y --no-install-recommends "postgresql-18-partman=${PARTMAN_VERSION}"; \
    rm -rf /var/lib/apt/lists/*

# The only Go binary in the image is upstream's gosu, which the entrypoints run once as root to step
# down to the postgres user. apt cannot reach it, so its Go stdlib CVEs — 22 HIGH/CRITICAL against
# Go 1.24.6 when this was written — leave the image only when the binary does. setpriv (util-linux)
# makes the identical switch and is patched by ordinary OS updates. The entrypoints hardcode the
# command name, so the stand-in takes the same path; gosu-setpriv.sh explains the argument handling.
#
# The removal is its own layer on purpose: an image scanner reads a path that a later layer merely
# overwrote as the file the lower layer put there, so the Go binary has to be deleted — which writes
# a whiteout — before the stand-in is copied over the same path.
#
# The build asserts the identity the entrypoints actually depend on — uid, primary gid and the
# supplementary groups, postgres being in ssl-cert too — rather than trusting the stand-in.
RUN rm /usr/local/bin/gosu
COPY gosu-setpriv.sh /usr/local/bin/gosu
RUN set -eux; \
    chmod 0755 /usr/local/bin/gosu; \
    expected="$(id -u postgres):$(id -g postgres):$(id -G postgres)"; \
    actual="$(gosu postgres sh -c 'echo "$(id -u):$(id -g):$(id -G)"')"; \
    [ "${expected}" = "${actual}" ]
