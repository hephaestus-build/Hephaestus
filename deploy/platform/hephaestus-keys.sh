#!/bin/sh
# Keep the release Secret of Hephaestus: docs/admin/kubernetes.mdx § Create the Secret.
#
#   sh hephaestus-keys.sh [--namespace hephaestus] [--secret hephaestus-app] [--backups] [--metrics]
#                         [--external-database [--new-keys]] [--external-nats]
#   sh hephaestus-keys.sh [--namespace …] [--secret …] --export-keys FILE
#   sh hephaestus-keys.sh [--namespace …] [--secret …] --check-keys FILE
#   sh hephaestus-keys.sh [--namespace …] [--secret …] --rotate KEY
#
# --export-keys writes the keys that a restore needs to a new file that only you can read, and
# --check-keys tells for each key whether that file still matches the Secret. Both change nothing.
# --rotate replaces a key whose rotation needs only a new value and a restart of the roles that
# read it: HEPHAESTUS_AUTH_STATE_COOKIE_KEY, or WEBHOOK_ROUTING_SECRET with its previous value.
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
METRICS=false
EXTERNAL_DATABASE=false
EXTERNAL_NATS=false
NEW_KEYS=false
EXPORT=
CHECK=
ROTATE=

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
	--metrics) METRICS=true; shift ;;
	--external-database) EXTERNAL_DATABASE=true; shift ;;
	--external-nats) EXTERNAL_NATS=true; shift ;;
	--new-keys) NEW_KEYS=true; shift ;;
	--export-keys) [ $# -ge 2 ] && [ -n "$2" ] || die "--export-keys needs a file"; EXPORT=$2; shift 2 ;;
	--check-keys) [ $# -ge 2 ] && [ -n "$2" ] || die "--check-keys needs a file"; CHECK=$2; shift 2 ;;
	--rotate) [ $# -ge 2 ] && [ -n "$2" ] || die "--rotate needs a key name"; ROTATE=$2; shift 2 ;;
	*) die "unknown argument: $1" ;;
	esac
done
modes=0
for mode in "$EXPORT" "$CHECK" "$ROTATE"; do [ -z "$mode" ] || modes=$((modes + 1)); done
if [ "$modes" -gt 0 ] && { [ "$modes" -gt 1 ] || [ "$BACKUPS$METRICS$EXTERNAL_DATABASE$EXTERNAL_NATS$NEW_KEYS" != falsefalsefalsefalsefalse ]; }; then
	die "--export-keys, --check-keys and --rotate each run alone, with at most --namespace and --secret"
fi
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
# jq on Windows ends each output line with CRLF, and $(...) strips only the LF, so every jq output
# that the script reads drops its CR.
existing=$(printf '%s' "$secret_json" | jq -r '.data // {} | keys[]' | tr -d '\r')
has() { printf '%s\n' "$existing" | grep -qx "$1"; }
# The keys that a restore needs: docs/admin/secret-custody.mdx § Keep the required keys outside the cluster.
RESTORE_KEYS='^(HEPHAESTUS_SECURITY_(CREDENTIAL_|PRIOR_CREDENTIAL_)?ENCRYPTION_KEY|DATABASE_USERNAME|BACKUP_CIPHER_PASS)$'
value_of() { printf '%s' "$secret_json" | jq -r --arg key "$1" '.data[$key] // empty | @base64d' | tr -d '\r'; }

if [ -n "$EXPORT" ]; then
	[ -n "$secret_json" ] || die "the Secret $NAMESPACE/$SECRET does not exist: there is nothing to back up"
	{ has HEPHAESTUS_SECURITY_ENCRYPTION_KEY && has HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY; } ||
		die "the Secret $NAMESPACE/$SECRET lacks an encryption key: restore it before you back it up"
	# An empty key restores nothing, so a file that holds one is no backup.
	empty=$(printf '%s' "$secret_json" | jq -r --arg keys "$RESTORE_KEYS" \
		'[.data | to_entries[] | select(.key | test($keys)) | select(.value | @base64d == "") | .key] | join(" ")' | tr -d '\r')
	[ -z "$empty" ] || die "the Secret $NAMESPACE/$SECRET holds an empty $empty: restore it before you back it up"
	directory=$(cd "$(dirname "$EXPORT")" && pwd -P) || die "the folder of $EXPORT does not exist"
	case $directory in /var/lib/rancher | /var/lib/rancher/*) die "write the file outside the k3s data folder" ;; esac
	[ ! -e "$EXPORT" ] && [ ! -L "$EXPORT" ] || die "$EXPORT exists: name a new file, so that no older copy is overwritten"
	# A line break would split a value over two lines of the file, which then restores the wrong key.
	breaks=$(printf '%s' "$secret_json" | jq -r --arg keys "$RESTORE_KEYS" \
		'[.data | to_entries[] | select(.key | test($keys)) | .value | @base64d | select(test("[\r\n]"))] | length' | tr -d '\r')
	[ "$breaks" = 0 ] || die "a key of the Secret holds a line break, so a file cannot keep it: copy it with another tool"
	printf '%s' "$secret_json" | jq -r --arg keys "$RESTORE_KEYS" \
		'.data | to_entries[] | select(.key | test($keys)) | "\(.key)=\(.value | @base64d)"' | tr -d '\r' >"$TEMP/keys.env"
	[ -s "$TEMP/keys.env" ] || die "the Secret $NAMESPACE/$SECRET holds none of the keys that a restore needs"
	names=$(printf '%s' "$secret_json" | jq -r --arg keys "$RESTORE_KEYS" '[.data | keys[] | select(test($keys))] | join(" ")' | tr -d '\r')
	# umask 077 makes the new file 0600; noclobber refuses a file that appeared since the check above.
	(set -C && cat "$TEMP/keys.env" >"$EXPORT") || die "could not write $EXPORT"
	printf 'Wrote %s, readable only by you, with: %s\n' "$EXPORT" "$names"
	echo "Move it off this host, to a password manager or offline storage, and delete it here."
	exit 0
fi

if [ -n "$CHECK" ]; then
	[ -f "$CHECK" ] || die "no file: $CHECK"
	[ -n "$secret_json" ] || die "the Secret $NAMESPACE/$SECRET does not exist"
	status=0
	seen=
	carriage=$(printf '\r')
	while IFS= read -r line || [ -n "$line" ]; do
		line=${line%"$carriage"}
		case $line in '' | '#'*) continue ;; *=*) ;; *) die "$CHECK holds a line that is not KEY=VALUE" ;; esac
		key=${line%%=*}
		value=${line#*=}
		printf '%s\n' "$key" | grep -Eq '^[A-Z][A-Z0-9_]*$' || die "$CHECK holds a line that is not KEY=VALUE"
		seen="$seen $key"
		if ! has "$key"; then
			echo "$key: not in the Secret"
			status=1
		# The encoded values compare exactly: $(...) would drop a trailing line break from the Secret value.
		elif [ "$(printf '%s' "$secret_json" | jq -r --arg key "$key" '.data[$key]' | tr -d '\r')" = \
			"$(printf '%s' "$value" | openssl base64 -A | tr -d '\r\n')" ]; then
			echo "$key: matches"
		else
			echo "$key: differs from the Secret"
			status=1
		fi
	done <"$CHECK"
	[ -n "$seen" ] || die "$CHECK holds no key"
	for key in $existing; do
		printf '%s\n' "$key" | grep -Eq "$RESTORE_KEYS" || continue
		case " $seen " in *" $key "*) ;; *)
			echo "$key: not in the file"
			status=1
			;;
		esac
	done
	exit "$status"
fi

: >"$TEMP/secret.env"
added=
add() {
	# An empty value, for example from a failed openssl, would be kept by every later run.
	[ -n "$2" ] || die "no value for $1: nothing was changed"
	case $2 in *'
'* | *"$(printf '\r')"*) die "the value of $1 holds a line break" ;;
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
# Random bytes as one line. openssl on Windows ends base64 lines with CRLF, and $(...) strips only the LF.
random() { openssl rand "$@" | tr -d '\r\n'; }

if [ -n "$ROTATE" ]; then
	[ -n "$secret_json" ] || die "the Secret $NAMESPACE/$SECRET does not exist: there is nothing to rotate"
	case $ROTATE in
	HEPHAESTUS_AUTH_STATE_COOKIE_KEY)
		add HEPHAESTUS_AUTH_STATE_COOKIE_KEY "$(random -base64 32)"
		roles=server
		;;
	WEBHOOK_ROUTING_SECRET)
		has WEBHOOK_ROUTING_SECRET || die "the Secret has no WEBHOOK_ROUTING_SECRET to rotate: run this without --rotate first"
		# The receiver accepts the current and one previous value. A second rotation would drop the
		# previous one, and every hook that it signed would stop.
		! has WEBHOOK_ROUTING_PREVIOUS_SECRET ||
			die "WEBHOOK_ROUTING_PREVIOUS_SECRET is set, so a rotation would drop it and stop the hooks that it signed: retire it first, as the Retire the previous routing secret section of Secret custody describes"
		add WEBHOOK_ROUTING_PREVIOUS_SECRET "$(value_of WEBHOOK_ROUTING_SECRET)"
		add WEBHOOK_ROUTING_SECRET "$(random -hex 32)"
		# The webhook role first: a server that already signs with the new value must not register
		# a hook that a webhook role with the old values refuses.
		roles="webhook server"
		;;
	*) die "--rotate takes HEPHAESTUS_AUTH_STATE_COOKIE_KEY or WEBHOOK_ROUTING_SECRET: the Rotate a value section of Secret custody gives the procedure for $ROTATE" ;;
	esac
	# A role reads the Secret only at its start, so each role must restart. Find each one before the
	# Secret changes: a selector that matches nothing restarts nothing and still succeeds.
	deployments=
	for role in $roles; do
		found=$(kubectl -n "$NAMESPACE" get deployment -l "app.kubernetes.io/component=$role" -o name)
		[ "$(printf '%s\n' "$found" | grep -c .)" = 1 ] ||
			die "the namespace $NAMESPACE needs exactly one $role Deployment for --rotate: nothing was changed"
		deployments="$deployments $found"
	done
	jq -Rn '[inputs | capture("^(?<key>[^=]+)=(?<value>.*)$")] | {stringData: (map({(.key): .value}) | add)}' \
		<"$TEMP/secret.env" >"$TEMP/secret-patch.json"
	kubectl -n "$NAMESPACE" patch secret "$SECRET" --type merge --patch-file "$TEMP/secret-patch.json" >/dev/null
	echo "Rotated $ROTATE"
	remaining=$deployments
	for deployment in $deployments; do
		kubectl -n "$NAMESPACE" rollout restart "$deployment" >/dev/null ||
			die "the Secret has the new value, but these did not restart:$remaining. Restart them with kubectl -n $NAMESPACE rollout restart, and do not run --rotate again"
		remaining=${remaining#" $deployment"}
		# A restart returns before the new pods run. The webhook pods that still run read only the old
		# routing value, so the server must not sign a hook with the new one until they are replaced.
		kubectl -n "$NAMESPACE" rollout status "$deployment" --timeout=10m >/dev/null ||
			die "$deployment restarted, but it is not ready after 10 minutes. Check it with kubectl -n $NAMESPACE rollout status $deployment${remaining:+, then restart what did not restart yet:$remaining}. Do not run --rotate again"
		echo "Restarted $deployment"
	done
	exit 0
fi

if [ "$EXTERNAL_DATABASE" = false ]; then
	database=$(kubectl -n "$NAMESPACE" get pvc -l app.kubernetes.io/component=postgresql -o name)
	if [ -n "$database" ] && ! { has DATABASE_USERNAME && has DATABASE_PASSWORD &&
		has HEPHAESTUS_SECURITY_ENCRYPTION_KEY && has HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY; }; then
		die "the PostgreSQL volume exists, but the Secret $SECRET lacks its database login or an encryption key. A new value would lock the database or lose what it encrypts. Restore the Secret from your backup, and run this again"
	fi
	has DATABASE_USERNAME || add DATABASE_USERNAME hephaestus
	has DATABASE_PASSWORD || add DATABASE_PASSWORD "$(random -hex 16)"
else
	has DATABASE_USERNAME || supply DATABASE_USERNAME "--external-database: set DATABASE_USERNAME to the login of your PostgreSQL"
	has DATABASE_PASSWORD || supply DATABASE_PASSWORD "--external-database: set DATABASE_PASSWORD to the password of that login"
	# This script cannot see whether your PostgreSQL holds data, so new keys need an explicit choice.
	if [ "$NEW_KEYS" = false ] && { ! has HEPHAESTUS_SECURITY_ENCRYPTION_KEY || ! has HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY; }; then
		die "the Secret $SECRET lacks an encryption key. If your PostgreSQL holds Hephaestus data, restore both keys from your backup: new keys cannot read that data. For a database without Hephaestus data, run this again with --new-keys"
	fi
fi
# "heph" and random hex, or nothing when openssl fails: a bare "heph" would pass the check in add.
heph_random() { suffix=$(random -hex 16) && [ -n "$suffix" ] && printf 'heph%s' "$suffix"; }
if [ "$EXTERNAL_NATS" = false ]; then
	has NATS_USERNAME || add NATS_USERNAME "$(heph_random)"
	has NATS_PASSWORD || add NATS_PASSWORD "$(heph_random)"
else
	has NATS_USERNAME || supply NATS_USERNAME "--external-nats: set NATS_USERNAME to the login of your NATS broker"
	has NATS_PASSWORD || supply NATS_PASSWORD "--external-nats: set NATS_PASSWORD to the password of that login"
fi
# Each key gets its own value (docs/admin/secret-custody.mdx § Generation and rotation).
has HEPHAESTUS_SECURITY_ENCRYPTION_KEY || add HEPHAESTUS_SECURITY_ENCRYPTION_KEY "$(random -hex 16)"
has HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY || add HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY "$(random -hex 16)"
has HEPHAESTUS_AUTH_STATE_COOKIE_KEY || add HEPHAESTUS_AUTH_STATE_COOKIE_KEY "$(random -base64 32)"
has WEBHOOK_SECRET || add WEBHOOK_SECRET "$(random -hex 32)"
has WEBHOOK_ROUTING_SECRET || add WEBHOOK_ROUTING_SECRET "$(random -hex 32)"
if [ "$BACKUPS" = true ]; then
	has BACKUP_CIPHER_PASS || add BACKUP_CIPHER_PASS "$(random -base64 48)"
fi
# The login of hephaestus_monitor, for postgresql.metrics.enabled.
if [ "$METRICS" = true ]; then
	has DATABASE_MONITOR_PASSWORD || add DATABASE_MONITOR_PASSWORD "$(random -hex 24)"
fi

changed=
client_secret=$(printenv GITHUB_OAUTH_CLIENT_SECRET || true)
if [ -n "$client_secret" ]; then
	current=$(printf '%s' "$secret_json" | jq -r '.data.GITHUB_OAUTH_CLIENT_SECRET // empty | @base64d' | tr -d '\r')
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
