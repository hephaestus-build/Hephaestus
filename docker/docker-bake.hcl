# The Dockerfile images that `.github/workflows/reusable-docker-build.yml` builds for one platform
# in one job. The workflow sets the variables below from the environment and the platform with
# `--set *.platform=…`. Each `<image>-metadata` target comes from docker/metadata-action and carries
# the standard OCI labels; a target's own labels override them.

variable "REGISTRY" {
  default = "ghcr.io"
}

# The platform as a tag-safe pair, such as linux-amd64: each platform keeps its own build cache.
variable "PLATFORM_PAIR" {
  default = "linux-amd64"
}

# "true" pushes each image by digest only; the manifest job publishes tags after verification.
variable "PUBLISH" {
  default = "false"
}

# "true" only where the build cache may be written: merge groups and the default branch.
variable "WRITE_CACHE" {
  default = "false"
}

variable "SOURCE_COMMIT" {
  default = ""
}

variable "COOLIFY_BRANCH" {
  default = ""
}

function "repository" {
  params = [name]
  result = "${REGISTRY}/hephaestus-build/${name}"
}

target "image" {
  # A refreshed package stage does not invalidate cached descendants in BuildKit.
  no-cache-filter = ["os-packages", "runtime"]
}

target "webapp" {
  inherits   = ["image", "webapp-metadata"]
  labels = {
    "org.opencontainers.image.title" = "Hephaestus Webapp"
    "org.opencontainers.image.description" = "Web client for Hephaestus"
    "org.opencontainers.image.vendor" = "AET TUM"
    "org.opencontainers.image.licenses" = "MIT"
    "hephaestus.component" = "webapp"
  }
  context    = "."
  dockerfile = "webapp/Dockerfile"
  args = {
    SOURCE_COMMIT  = SOURCE_COMMIT
    COOLIFY_BRANCH = COOLIFY_BRANCH
  }
  cache-from = ["type=registry,ref=${repository("webapp")}:cache-main-${PLATFORM_PAIR}"]
  cache-to   = WRITE_CACHE == "true" ? ["type=registry,ref=${repository("webapp")}:cache-main-${PLATFORM_PAIR},mode=max"] : []
  output     = PUBLISH == "true" ? ["type=image,\"name=${repository("webapp")}\",push-by-digest=true,name-canonical=true,push=true"] : ["type=cacheonly"]
}

target "agent-pi" {
  inherits   = ["image", "agent-pi-metadata"]
  labels = {
    "org.opencontainers.image.title" = "Hephaestus Agent - Pi"
    "org.opencontainers.image.description" = "Sandboxed Pi coding agent for AI-powered code review"
    "org.opencontainers.image.vendor" = "AET TUM"
    "org.opencontainers.image.licenses" = "MIT"
    "hephaestus.component" = "agent-pi"
  }
  context    = "docker/agents"
  dockerfile = "pi/Dockerfile"
  cache-from = ["type=registry,ref=${repository("agent-pi")}:cache-main-${PLATFORM_PAIR}"]
  cache-to   = WRITE_CACHE == "true" ? ["type=registry,ref=${repository("agent-pi")}:cache-main-${PLATFORM_PAIR},mode=max"] : []
  output     = PUBLISH == "true" ? ["type=image,\"name=${repository("agent-pi")}\",push-by-digest=true,name-canonical=true,push=true"] : ["type=cacheonly"]
}

target "postgres" {
  inherits   = ["image", "postgres-metadata"]
  labels = {
    "org.opencontainers.image.title" = "Hephaestus Postgres"
    "org.opencontainers.image.description" = "PostgreSQL + pg_partman for all environments (dev/preview/prod)"
    "org.opencontainers.image.vendor" = "AET TUM"
    "org.opencontainers.image.licenses" = "MIT"
    "hephaestus.component" = "postgres"
  }
  context    = "docker/postgres"
  dockerfile = "Dockerfile"
  cache-from = ["type=registry,ref=${repository("postgres")}:cache-main-${PLATFORM_PAIR}"]
  cache-to   = WRITE_CACHE == "true" ? ["type=registry,ref=${repository("postgres")}:cache-main-${PLATFORM_PAIR},mode=max"] : []
  output     = PUBLISH == "true" ? ["type=image,\"name=${repository("postgres")}\",push-by-digest=true,name-canonical=true,push=true"] : ["type=cacheonly"]
}
