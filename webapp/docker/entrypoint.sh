#!/bin/bash
set -euo pipefail

# The build output is read-only and stays byte-for-byte as built. Everything this script writes
# goes under RUNTIME_DIR, which nginx.conf serves for the index, the env config and the discovery file.
readonly HTML_DIR="/usr/share/nginx/html"
readonly INDEX_TEMPLATE="${HTML_DIR}/index.html"
readonly RUNTIME_DIR="/tmp/hephaestus-webapp"
readonly ENV_TS="/app/src/environment/index.ts"

log() { echo "[$(date +'%Y-%m-%dT%H:%M:%S%z')] $*" >&2; }

escape_for_js() {
  local input="${1:-}" output="" i char
  for ((i=0; i<${#input}; i++)); do
    char="${input:i:1}"
    case "$char" in
      $'\n') output+="\\n" ;; $'\r') output+="\\r" ;; $'\t') output+="\\t" ;;
      '"') output+="\\\"" ;; '\') output+="\\\\" ;; *) output+="$char" ;;
    esac
  done
  printf '%s' "$output"
}

# Extract variable names from TypeScript interface (single source of truth)
# Parses: "VAR_NAME?: string;" patterns from RuntimeEnvVars interface
extract_env_vars() {
  if [[ ! -f "$ENV_TS" ]]; then
    log "ERROR: TypeScript source not found: $ENV_TS"
    exit 1
  fi
  grep -oE '[A-Z_]+\?:' "$ENV_TS" | sed 's/\?:$//' | grep -v '^__'
}

# The file names the operator of this deployment, so without SECURITY_CONTACT there is none: a report
# about an installation goes to whoever runs it. RFC 9116 § 2.5.2: a reader distrusts the file on a
# host that Canonical does not list, and a web Canonical is https. Workspace addresses serve it on
# many hosts, so only a single https origin gets one; Canonical is optional.
write_security_txt() {
  local file="${RUNTIME_DIR}/.well-known/security.txt" expires
  if [[ -z "${SECURITY_CONTACT:-}" ]]; then
    rm -f "$file"
    return
  fi
  # RFC 9116 § 2.5.5 recommends an expiry less than a year away. Each start renews it, and every
  # release restarts the webapp.
  TZ=UTC printf -v expires '%(%Y-%m-%dT%H:%M:%SZ)T' "$(( $(printf '%(%s)T' -1) + 364 * 86400 ))"
  {
    echo "Contact: ${SECURITY_CONTACT}"
    if [[ "${HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED:-false}" == false \
      && "${APPLICATION_CLIENT_URL:-}" =~ ^https://[^[:space:]/]+/?$ ]]; then
      echo "Canonical: ${APPLICATION_CLIENT_URL%/}/.well-known/security.txt"
    fi
    echo "Expires: ${expires}"
  } > "$file"
}

main() {
  # Recover git metadata from build-time variables if runtime vars are empty/missing
  # An empty runtime GIT_COMMIT or GIT_BRANCH would otherwise override the baked values.
  if [[ -z "${GIT_COMMIT:-}" && -n "${BUILD_GIT_COMMIT:-}" ]]; then
    export GIT_COMMIT="$BUILD_GIT_COMMIT"
    log "Restored GIT_COMMIT from build artifact: $GIT_COMMIT"
  fi
  
  if [[ -z "${GIT_BRANCH:-}" && -n "${BUILD_GIT_BRANCH:-}" ]]; then
    export GIT_BRANCH="$BUILD_GIT_BRANCH"
    log "Restored GIT_BRANCH from build artifact: $GIT_BRANCH"
  elif [[ "${GIT_BRANCH:-}" == "main" && -n "${BUILD_GIT_BRANCH:-}" && "${BUILD_GIT_BRANCH}" != "main" ]]; then
    # Prefer specific branch from build if runtime says generic "main"
    export GIT_BRANCH="$BUILD_GIT_BRANCH"
    log "Restored specific GIT_BRANCH from build artifact: $GIT_BRANCH"
  fi

  log "Generating runtime config..."

  local subdomains="${HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED:-false}"
  case "$subdomains" in true|false) ;; *) log "Invalid workspace subdomains switch"; exit 1 ;; esac
  if [[ "$subdomains" == true && -z "${APPLICATION_CLIENT_URL:-}" ]]; then
    log "ERROR: APPLICATION_CLIENT_URL is required when workspace subdomains are enabled."
    exit 1
  fi
  if [[ -n "${SECURITY_CONTACT:-}" && ! "$SECURITY_CONTACT" =~ ^(mailto:|https://|tel:)[^[:space:]]+$ ]]; then
    log "ERROR: SECURITY_CONTACT must be one mailto:, https:// or tel: URI."
    exit 1
  fi
  if [[ ! -f "$INDEX_TEMPLATE" ]]; then
    log "ERROR: index.html not found: $INDEX_TEMPLATE"
    exit 1
  fi
  mkdir -p "${RUNTIME_DIR}/.well-known"
  printf '{"apexOrigin":"%s","baseDomain":"%s","workspaceSubdomainsEnabled":%s}\n' \
    "$(escape_for_js "${APPLICATION_CLIENT_URL:-}")" \
    "$(escape_for_js "${HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN:-}")" \
    "$subdomains" > "${RUNTIME_DIR}/.well-known/hephaestus.json"
  write_security_txt

  # Extract all env var names from TypeScript (single source of truth)
  local -a all_vars=()
  local var
  while IFS= read -r var; do all_vars+=("$var"); done < <(extract_env_vars)
  
  if [[ ${#all_vars[@]} -eq 0 ]]; then
    log "ERROR: No environment variables found in $ENV_TS"
    exit 1
  fi
  log "Found ${#all_vars[@]} vars: ${all_vars[*]}"

  # Auto-generate deployment timestamp for preview environments
  if [[ -n "${GIT_BRANCH:-}" && -z "${DEPLOYED_AT:-}" ]]; then
    export DEPLOYED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    log "Generated DEPLOYED_AT: ${DEPLOYED_AT}"
  fi

  # Log which vars are set/unset
  local -a present=() missing=()
  for var in "${all_vars[@]}"; do
    [[ -n "${!var:-}" ]] && present+=("$var") || missing+=("$var")
  done
  [[ ${#present[@]} -gt 0 ]] && log "Set: ${present[*]}"
  [[ ${#missing[@]} -gt 0 ]] && log "Unset: ${missing[*]}"

  # Determine which vars to include in cache hash
  # Preview/dev (GIT_BRANCH set): all vars are cacheable (same deployment = same hash)
  # Production (no GIT_BRANCH): exclude DEPLOYED_AT (auto-generated timestamp would bust cache)
  local -a hash_vars=()
  for var in "${all_vars[@]}"; do
    if [[ "$var" == "DEPLOYED_AT" && -z "${GIT_BRANCH:-}" ]]; then
      continue  # Exclude DEPLOYED_AT from hash in production
    fi
    hash_vars+=("$var")
  done

  # Compute content hash from cacheable vars
  local hash_input=""
  for var in "${hash_vars[@]}"; do
    hash_input+="${var}=${!var:-}"$'\n'
  done
  local hash
  hash=$(printf '%s' "$hash_input" | sha256sum | cut -c1-8)

  # Generate JavaScript config
  local filename="env-config.${hash}.js"
  {
    echo "window.__ENV__ = {"
    local first=true
    for var in "${all_vars[@]}"; do
      $first && first=false || echo ","
      printf '  %s: "%s"' "$var" "$(escape_for_js "${!var:-}")"
    done
    echo -e "\n};"
  } > "${RUNTIME_DIR}/${filename}"

  # A restart can keep RUNTIME_DIR (a Kubernetes emptyDir does), so remove the previous config.
  find "${RUNTIME_DIR}" -maxdepth 1 -name 'env-config.*.js' ! -name "$filename" -delete 2>/dev/null || true
  log "Created ${filename}"

  # Generate the served index from the immutable build output on every start.
  sed -E "s|/env-config(\.[a-f0-9]{8})?\.js|/${filename}|g" "$INDEX_TEMPLATE" > "${RUNTIME_DIR}/index.html"
  grep -q "$filename" "${RUNTIME_DIR}/index.html" || { log "ERROR: Failed to update index.html"; exit 1; }
  log "Generated index.html"

  log "Done (hash: ${hash})"
}

main
