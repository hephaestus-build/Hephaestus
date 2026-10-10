#!/bin/bash
set -euo pipefail

readonly HTML_DIR="/usr/share/nginx/html"
readonly INDEX_HTML="${HTML_DIR}/index.html"
readonly ENV_TS="/app/src/environment/index.ts"

# Must match LEGAL_PROFILE_PATTERN_SOURCE in webapp/src/lib/legal.ts. A unit
# test pins them together — keep both in sync if you widen the policy.
readonly LEGAL_PROFILE_PATTERN='^[a-z0-9][a-z0-9_-]{0,31}$'

log() { echo "[$(date +'%Y-%m-%dT%H:%M:%S%z')] $*" >&2; }

escape_for_js() {
  local input="${1:-}" output="" i char
  for ((i=0; i<${#input}; i++)); do
    char="${input:i:1}"
    case "$char" in
      $'\n') output+="\\n" ;; $'\r') output+="\\r" ;; $'\t') output+="\\t" ;;
      '"') output+="\\\"" ;; '\\') output+="\\\\" ;; *) output+="$char" ;;
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

main() {
  # Recover git metadata from build-time variables if runtime vars are empty/missing
  # Coolify tends to inject empty GIT_COMMIT/SOURCE_COMMIT at runtime, overriding baked ENV vars.
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

  # Heuristic to detect PR deployment and fix GIT_BRANCH if it defaulted to "main"
  # Coolify PR deployments often report branch as "main" (target) instead of source.
  if [[ "${GIT_BRANCH:-}" == "main" && -n "${SERVICE_FQDN_WEBAPP:-}" ]]; then
    # Match pattern like pr622. or pr-622. (Coolify default usually pr<id>.)
    if [[ "$SERVICE_FQDN_WEBAPP" =~ ^pr-?([0-9]+) ]]; then
       local pr_id="${BASH_REMATCH[1]}"
       local detected_branch="PR-${pr_id}"

       # Try to resolve actual branch name via GitHub API if token is available
       if [[ -n "${GH_AUTH_TOKEN:-}" ]]; then
         local api_url="https://api.github.com/repos/hephaestus-build/Hephaestus/pulls/${pr_id}"
         local pr_json
         if pr_json=$(curl --connect-timeout 5 --max-time 10 -sS -H "Authorization: Bearer $GH_AUTH_TOKEN" "$api_url"); then
           # Extract head.ref safely using grep/sed (no jq in image)
           # JSON format: "head": { ... "ref": "branch-name", ... }
           local branch_ref
           branch_ref=$(echo "$pr_json" | sed -n '/"head": {/,/}/p' | grep '"ref":' | head -1 | sed -E 's/.*"ref": "([^"]+)".*/\1/')
           if [[ -n "$branch_ref" ]]; then
             detected_branch="$branch_ref"
             log "Resolved real branch from GitHub API: $detected_branch"
           else
             log "WARNING: Failed to parse branch from GitHub API response"
           fi
         else
           log "WARNING: Failed to fetch PR details from GitHub API"
         fi
       fi

       export GIT_BRANCH="$detected_branch"
       log "Detected PR deployment from FQDN. Overriding GIT_BRANCH to: $GIT_BRANCH"
    fi
  fi

  log "Generating runtime config..."

  # Extract all env var names from TypeScript (single source of truth)
  local -a all_vars
  mapfile -t all_vars < <(extract_env_vars)
  
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

  # Legal profile sanity check: an invalid or unbundled value silently falls
  # through to the built-in disclaimer, which is a § 5 DDG / Art. 13 GDPR
  # violation if served in production. Emit a discoverable WARN so operators
  # notice in `docker logs` / Coolify / kubectl logs.
  if [[ -n "${LEGAL_PROFILE:-}" ]]; then
    local profiles_dir="${HTML_DIR}/legal/profiles"
    if [[ ! "$LEGAL_PROFILE" =~ $LEGAL_PROFILE_PATTERN ]]; then
      log "WARN: LEGAL_PROFILE='${LEGAL_PROFILE}' is not a valid profile name (expected lowercase [a-z0-9_-], max 32 chars). Falling back to the built-in disclaimer. See docs/admin/legal-pages.mdx."
    elif [[ ! -d "${profiles_dir}/${LEGAL_PROFILE}" ]]; then
      local -a available=()
      if [[ -d "$profiles_dir" ]]; then
        while IFS= read -r -d '' dir; do available+=("$(basename "$dir")"); done < <(find "$profiles_dir" -mindepth 1 -maxdepth 1 -type d -print0)
      fi
      log "WARN: LEGAL_PROFILE='${LEGAL_PROFILE}' has no bundled profile at ${profiles_dir}/${LEGAL_PROFILE}. Available: ${available[*]:-none}. Falling back to the built-in disclaimer unless /legal-overrides/ is mounted. See docs/admin/legal-pages.mdx."
    else
      log "Legal profile '${LEGAL_PROFILE}' resolved to ${profiles_dir}/${LEGAL_PROFILE}."
    fi
  fi

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
  } > "${HTML_DIR}/${filename}"

  # Remove old config files and update index.html
  find "${HTML_DIR}" -maxdepth 1 -name 'env-config.*.js' ! -name "$filename" -delete 2>/dev/null || true
  log "Created ${filename}"

  if [[ -f "$INDEX_HTML" ]]; then
    sed -Ei "s|/env-config(\.[a-f0-9]{8})?\.js|/${filename}|g" "$INDEX_HTML"
    grep -q "$filename" "$INDEX_HTML" || { log "ERROR: Failed to update index.html"; exit 1; }
    log "Updated index.html"
  fi

  log "Done (hash: ${hash})"
}

main
