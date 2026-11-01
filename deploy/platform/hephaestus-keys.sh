#!/bin/sh
# Keep the release Secret of Hephaestus: docs/admin/kubernetes.mdx § Create the Secret.
#
#   sh hephaestus-keys.sh [--namespace hephaestus] [--secret hephaestus-app] [--backups]
#                         [--external-database [--new-keys]] [--external-nats]
#
# It uses the current kubectl context. It creates the Secret, or adds only the keys that the Secret
# lacks, and it never changes a key that exists. It prints key names, never a value.
# k3s-install.sh runs it on every install; on another cluster, run it once before the first
# `helm upgrade --install`, also when Git holds the rest of the release.
#
# Values that it cannot generate come from the environment, and only when they are set:
#   GITHUB_OAUTH_CLIENT_SECRET          stored when it differs from the Secret.
#   DATABASE_USERNAME, DATABASE_PASSWORD the login of your PostgreSQL, with --external-database.
#                                       New encryption keys for that database need --new-keys.
#   NATS_USERNAME, NATS_PASSWORD        the login of your NATS broker, with --external-nats.
set -eu

NAMESPACE=hephaestus
SECRET=hephaestus-app
BACKUPS=false
EXTERNAL_DATABASE=false
EXTERNAL_NATS=false
NEW_KEYS=false

die() {
	printf 'hephaestus-keys: %s\n' "$*" >&2
	exit 1
}
warn() { printf 'hephaestus-keys: warning: %s\n' "$*" >&2; }

while [ $# -gt 0 ]; do
	case $1 in
	--namespace) [ $# -ge 2 ] || die "--namespace needs a value"; NAMESPACE=$2; shift 2 ;;
	--secret) [ $# -ge 2 ] || die "--secret needs a value"; SECRET=$2; shift 2 ;;
	--backups) BACKUPS=true; shift ;;
	--external-database) EXTERNAL_DATABASE=true; shift ;;
	--external-nats) EXTERNAL_NATS=true; shift ;;
	--new-keys) NEW_KEYS=true; shift ;;
	*) die "unknown argument: $1" ;;
	esac
done
[ "$NEW_KEYS" = false ] || [ "$EXTERNAL_DATABASE" = true ] || die "--new-keys needs --external-database: for the bundled PostgreSQL, the script decides from its volume"
printf '%s\n' "$NAMESPACE" | grep -Eq '^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$' || die "not a namespace name: $NAMESPACE"
printf '%s\n' "$SECRET" | grep -Eq '^[a-z0-9]([-.a-z0-9]{0,251}[a-z0-9])?$' || die "not a Secret name: $SECRET"
for tool in kubectl openssl jq; do
	command -v "$tool" >/dev/null 2>&1 || die "$tool is not installed"
done

umask 077
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
trap 'exit 1' HUP INT TERM

# An error of the API stops the run here: it must never read as "no Secret", which would make new keys.
secret_json=$(kubectl -n "$NAMESPACE" get secret "$SECRET" -o json --ignore-not-found)
existing=$(printf '%s' "$secret_json" | jq -r '.data // {} | keys[]')
has() { printf '%s\n' "$existing" | grep -qx "$1"; }

: >"$TEMP/secret.env"
added=
add() {
	# An empty value, for example from a failed openssl, would be kept by every later run.
	[ -n "$2" ] || die "no value for $1: nothing was changed"
	case $2 in *'
'*) die "the value of $1 holds a line break" ;;
	esac
	printf '%s=%s\n' "$1" "$2" >>"$TEMP/secret.env"
	added="$added $1"
}
# A value from the environment, for a key that the Secret lacks.
supply() {
	value=$(printenv "$1" || true)
	[ -n "$value" ] || die "$2"
	add "$1" "$value"
}

if [ "$EXTERNAL_DATABASE" = false ]; then
	database=$(kubectl -n "$NAMESPACE" get pvc -l app.kubernetes.io/component=postgresql -o name)
	if [ -n "$database" ] && ! { has DATABASE_USERNAME && has DATABASE_PASSWORD &&
		has HEPHAESTUS_SECURITY_ENCRYPTION_KEY && has HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY; }; then
		die "the PostgreSQL volume exists, but the Secret $SECRET lacks its database login or an encryption key. A new value would lock the database or lose what it encrypts. Restore the Secret from your backup, and run this again"
	fi
	has DATABASE_USERNAME || add DATABASE_USERNAME hephaestus
	has DATABASE_PASSWORD || add DATABASE_PASSWORD "$(openssl rand -hex 16)"
else
	has DATABASE_USERNAME || supply DATABASE_USERNAME "--external-database: set DATABASE_USERNAME to the login of your PostgreSQL"
	has DATABASE_PASSWORD || supply DATABASE_PASSWORD "--external-database: set DATABASE_PASSWORD to the password of that login"
	# This script cannot see whether your PostgreSQL holds data, so new keys need an explicit choice.
	if [ "$NEW_KEYS" = false ] && { ! has HEPHAESTUS_SECURITY_ENCRYPTION_KEY || ! has HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY; }; then
		die "the Secret $SECRET lacks an encryption key. If your PostgreSQL holds Hephaestus data, restore both keys from your backup: new keys cannot read that data. For a database without Hephaestus data, run this again with --new-keys"
	fi
fi
if [ "$EXTERNAL_NATS" = false ]; then
	has NATS_USERNAME || add NATS_USERNAME "heph$(openssl rand -hex 16)"
	has NATS_PASSWORD || add NATS_PASSWORD "heph$(openssl rand -hex 16)"
else
	has NATS_USERNAME || supply NATS_USERNAME "--external-nats: set NATS_USERNAME to the login of your NATS broker"
	has NATS_PASSWORD || supply NATS_PASSWORD "--external-nats: set NATS_PASSWORD to the password of that login"
fi
# Each key gets its own value (docs/admin/secret-custody.mdx § Generation and rotation).
has HEPHAESTUS_SECURITY_ENCRYPTION_KEY || add HEPHAESTUS_SECURITY_ENCRYPTION_KEY "$(openssl rand -hex 16)"
has HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY || add HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY "$(openssl rand -hex 16)"
has HEPHAESTUS_AUTH_STATE_COOKIE_KEY || add HEPHAESTUS_AUTH_STATE_COOKIE_KEY "$(openssl rand -base64 32)"
has WEBHOOK_SECRET || add WEBHOOK_SECRET "$(openssl rand -hex 32)"
has WEBHOOK_ROUTING_SECRET || add WEBHOOK_ROUTING_SECRET "$(openssl rand -hex 32)"
if [ "$BACKUPS" = true ]; then
	has BACKUP_CIPHER_PASS || add BACKUP_CIPHER_PASS "$(openssl rand -base64 48 | tr -d '\n')"
fi

changed=
client_secret=$(printenv GITHUB_OAUTH_CLIENT_SECRET || true)
if [ -n "$client_secret" ]; then
	current=$(printf '%s' "$secret_json" | jq -r '.data.GITHUB_OAUTH_CLIENT_SECRET // empty | @base64d')
	if [ "$current" != "$client_secret" ]; then
		add GITHUB_OAUTH_CLIENT_SECRET "$client_secret"
		# The server copies the client secret at its start, so a running server needs a restart.
		[ -z "$secret_json" ] || changed=GITHUB_OAUTH_CLIENT_SECRET
	fi
elif ! has GITHUB_OAUTH_CLIENT_SECRET; then
	warn "the Secret has no GITHUB_OAUTH_CLIENT_SECRET: GitHub sign-in fails until you run this again with it set"
fi

if [ -z "$secret_json" ]; then
	kubectl -n "$NAMESPACE" create secret generic "$SECRET" --from-env-file="$TEMP/secret.env" >/dev/null
elif [ -s "$TEMP/secret.env" ]; then
	jq -Rn '[inputs | capture("^(?<key>[^=]+)=(?<value>.*)$")] | {stringData: (map({(.key): .value}) | add)}' \
		<"$TEMP/secret.env" >"$TEMP/secret-patch.json"
	kubectl -n "$NAMESPACE" patch secret "$SECRET" --type merge --patch-file "$TEMP/secret-patch.json" >/dev/null
fi

for key in $added; do
	if [ "$key" = "$changed" ]; then
		echo "Changed $key"
	else
		echo "Added $key"
	fi
done
[ -n "$added" ] || echo "The Secret $NAMESPACE/$SECRET has every key: nothing changed"
