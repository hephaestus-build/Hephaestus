#!/bin/sh

set -eu
umask 077

directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
environment_file="$directory/.env"
example_file="$directory/.env.example"

command -v openssl >/dev/null 2>&1 || {
	printf '%s\n' "openssl is required to generate installation secrets." >&2
	exit 1
}

if [ -L "$environment_file" ]; then
	printf '%s\n' "Refusing to write through symlink $environment_file." >&2
	exit 1
fi

working_file=$(mktemp "$directory/.env.setup.XXXXXX")
generated_keys=
trap 'rm -f "$working_file"' EXIT HUP INT TERM

if [ -f "$environment_file" ]; then
	cp "$environment_file" "$working_file"
else
	cp "$example_file" "$working_file"
fi
chmod 600 "$working_file"

for key in POSTGRES_PASSWORD HEPHAESTUS_SECURITY_ENCRYPTION_KEY HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY HEPHAESTUS_AUTH_STATE_COOKIE_KEY WEBHOOK_SECRET WEBHOOK_ROUTING_SECRET NATS_USERNAME NATS_PASSWORD; do
	if [ "$(grep -c "^${key}=" "$working_file")" -gt 1 ]; then
		printf 'Refusing duplicate %s assignments in %s.\n' "$key" "$environment_file" >&2
		exit 1
	fi
done

set_if_empty() {
	key=$1
	format=$2
	if grep -q "^${key}=." "$working_file"; then
		return
	fi
	case "$format" in
		hex16) value=$(openssl rand -hex 16) || return 1 ;;
		hex32) value=$(openssl rand -hex 32) || return 1 ;;
		base64) value=$(openssl rand -base64 32 | tr -d '\n') || return 1 ;;
		# The broker reads these from its own config file, which interpolates them unquoted;
		# its parser reads an all-digit token as a number and exits before it listens. The
		# prefix keeps a letter in every generated value.
		broker) value=heph$(openssl rand -hex 16) || return 1 ;;
	esac
	if ! grep -q "^${key}=" "$working_file"; then
		printf '%s=%s\n' "$key" "$value" >> "$working_file"
		generated_keys="$generated_keys $key"
	elif grep -q "^${key}=$" "$working_file"; then
		temporary_file=$(mktemp "$directory/.env.value.XXXXXX")
		while IFS= read -r line; do
			if [ "$line" = "$key=" ]; then
				printf '%s=%s\n' "$key" "$value"
			else
				printf '%s\n' "$line"
			fi
		done < "$working_file" > "$temporary_file"
		chmod 600 "$temporary_file"
		mv "$temporary_file" "$working_file"
		generated_keys="$generated_keys $key"
	fi
}

# A key is generated for a new instance and must never be generated for an existing one: rows the
# database already holds were encrypted with the key that instance had, and a fresh key makes them
# unreadable without a word of warning. The supported topology keeps the database in the Compose
# volume hephaestus_postgresql-data, so that exact volume is the evidence. Docker's own listing is
# the only answer accepted: anything short of it leaves the question open, and no key is generated.
database_evidence=
database_evidence_detail=
database_evidence() {
	if [ -n "$database_evidence" ]; then
		return
	fi
	if ! command -v docker >/dev/null 2>&1; then
		database_evidence=unknown
		database_evidence_detail='docker is not installed on this host, or not on PATH'
		return
	fi
	if volumes=$(docker volume ls --quiet 2>&1); then
		if printf '%s\n' "$volumes" | grep -qx 'hephaestus_postgresql-data'; then
			database_evidence=present
		else
			database_evidence=absent
		fi
	else
		database_evidence=unknown
		database_evidence_detail="docker volume ls failed: $volumes"
	fi
}
refuse_unless_new_database() {
	key=$1
	database_evidence
	case $database_evidence in
		present)
			printf '%s\n' "A Hephaestus database already exists on this host (volume hephaestus_postgresql-data). Set $key to the value that database was written with before running setup; a generated key would make what it stores unreadable." >&2
			exit 1
			;;
		unknown)
			printf '%s\n' "Could not tell whether a Hephaestus database already exists on this host ($database_evidence_detail), so $key was not generated. Start Docker, or give setup a working docker, and run it again; if this host already ran Hephaestus, set $key to the value it used." >&2
			exit 1
			;;
	esac
}
# Whether the file carried a master key before anything here touched it. Only such a file is a
# credible pre-v0.75 configuration, the one shape in which an absent credential key means "the
# master key": a file that is empty or damaged proves nothing about how the database was written.
master_key_was_configured=false
if grep -q '^HEPHAESTUS_SECURITY_ENCRYPTION_KEY=.' "$working_file"; then
	master_key_was_configured=true
fi
credential_key_is_absent=true
if grep -q '^HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=.' "$working_file"; then
	credential_key_is_absent=false
fi
credential_key_was_set_separately=false
if grep -q '^HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=' "$working_file" \
	|| grep -qE '^HEPHAESTUS_SECURITY_(CREDENTIAL_ENCRYPTION_KEY_VERSION|PRIOR_CREDENTIAL_ENCRYPTION_KEY|PRIOR_CREDENTIAL_ENCRYPTION_KEY_VERSION|CREDENTIAL_ROTATION_ENABLED)=' "$working_file"; then
	credential_key_was_set_separately=true
fi
if [ "$master_key_was_configured" = false ]; then
	if [ "$credential_key_is_absent" = true ]; then
		refuse_unless_new_database 'HEPHAESTUS_SECURITY_ENCRYPTION_KEY and HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY (the same value as the master key on an installation from before v0.75)'
	else
		refuse_unless_new_database HEPHAESTUS_SECURITY_ENCRYPTION_KEY
	fi
fi
if [ "$credential_key_is_absent" = true ] && [ "$credential_key_was_set_separately" = true ]; then
	refuse_unless_new_database HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY
fi

set_if_empty POSTGRES_PASSWORD hex16
set_if_empty HEPHAESTUS_SECURITY_ENCRYPTION_KEY hex16
if ! grep -q '^HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=.' "$working_file"; then
	credential_key=$(sed -n 's/^HEPHAESTUS_SECURITY_ENCRYPTION_KEY=//p' "$working_file")
	if grep -q '^HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=' "$working_file"; then
		while IFS= read -r line; do
			if [ "$line" = 'HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=' ]; then
				printf 'HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=%s\n' "$credential_key"
			else
				printf '%s\n' "$line"
			fi
		done < "$working_file" > "$working_file.next"
		mv "$working_file.next" "$working_file"
	else
		printf 'HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=%s\n' "$credential_key" >> "$working_file"
	fi
	generated_keys="$generated_keys HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY"
fi
set_if_empty HEPHAESTUS_AUTH_STATE_COOKIE_KEY base64
set_if_empty WEBHOOK_SECRET hex32
set_if_empty WEBHOOK_ROUTING_SECRET hex32
set_if_empty NATS_USERNAME broker
set_if_empty NATS_PASSWORD broker

mv "$working_file" "$environment_file"

for key in $generated_keys; do
	printf 'Generated %s.\n' "$key"
done

printf '\nConfiguration written to %s. Generated values were not printed.\n' "$environment_file"
printf '%s\n' 'Set APP_HOSTNAME, ACME_EMAIL, one OAuth provider, and HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS before starting Hephaestus.'
