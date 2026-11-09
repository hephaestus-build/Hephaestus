#!/bin/sh
# The one-host install of Hephaestus on k3s: docs/admin/kubernetes.mdx § Install on one host (k3s).
#
# Run it as a user with sudo rights, from the folder where you unpacked the verified
# hephaestus-k3s-X.Y.Z.tar.gz bundle of one release:
#
#   sh k3s-install.sh --version 0.95.0 --host hephaestus.example.com --email ops@example.com
#
# It checks the host first, then installs the pinned k3s and the platform, keeps the release Secret,
# verifies the release chart and installs it. A second run with the same release changes nothing
# that the first one made: it generates only the keys that the Secret lacks and keeps site.yaml.
# It never prints a secret value. Release changes use the stopped schema-upgrade procedure.
set -eu

RELEASE_REPOSITORY=hephaestus-build/Hephaestus
CHART_REPOSITORY=ghcr.io/hephaestus-build/charts/hephaestus
SIGNER="https://github.com/$RELEASE_REPOSITORY/.github/workflows/release.yml@refs/heads/main"
ISSUER=https://token.actions.githubusercontent.com
MANIFESTS=/var/lib/rancher/k3s/server/manifests
NAMESPACE=hephaestus
SANDBOXES=hephaestus-sandboxes
SECRET=hephaestus-app
# The namespace of Traefik and its Gateway, as k3s-platform.yaml and k3s-certificates.yaml name it.
GATEWAY_NAMESPACE=traefik
# The small profile needs a 16 GiB host, less what the kernel keeps, and 4 vCPU.
SMALL_MEMORY_KIB=15728640
MIN_CPUS=4

say() { printf '==> %s\n' "$*"; }
warn() { printf 'k3s-install: warning: %s\n' "$*" >&2; }
die() {
	printf 'k3s-install: %s\n' "$*" >&2
	exit 1
}

usage() {
	cat >&2 <<'EOF'
usage: sh k3s-install.sh --version X.Y.Z --host NAME --email ADDRESS [options]

  --version X.Y.Z            the release of these files, without the leading "v"
  --host NAME                the public host name, which resolves to this host
  --email ADDRESS            the address that gets the expiry notices of Let's Encrypt
  --workspace-subdomains     enable workspace hosts; needs --dns01-issuer
  --dns01-issuer NAME         existing ClusterIssuer for a DNS-01 wildcard certificate
  --staging                  use the Let's Encrypt staging service: a certificate that no browser trusts
  --github-client-id ID      the client ID of the GitHub OAuth app, for a new site.yaml
  --bootstrap-admins LIST    for example github:1234567, for a new site.yaml
  --standard-certificates    explicitly render the standard certificates on a repeat install
  --certificates FILE        install FILE in place of k3s-certificates.yaml (the release smoke)
  --lock FILE                the joint lock of the release, already downloaded (a draft release)
  --skip-check NAME          skip one host check: dns, size or clock. Repeat it for each.
  --render-certificates      print the certificates file that this run would install, and stop
  --render-site              print the site.yaml that this run would write, and stop
  --verify-kubelet           check the running kubelet policy and allocatable, and stop
  --allow-unqualified-host   explicitly permit a host outside the Ubuntu 24.04/kernel 6.8 baseline

The environment variable GITHUB_OAUTH_CLIENT_SECRET, when set, goes into the release Secret.
On a terminal, the installer asks for each of these that a first install lacks: the release, the
host, the e-mail address, the GitHub OAuth app and its client secret, and your GitHub login.
EOF
	exit 2
}

# A value matches the extended regular expression in full, and it holds no line break.
matches() {
	case $1 in *'
'*) return 1 ;;
	esac
	printf '%s\n' "$1" | grep -Eq "^($2)\$"
}

VERSION=
HOST=
EMAIL=
STAGING=false
WORKSPACE_SUBDOMAINS=false
DNS01_ISSUER=
GITHUB_CLIENT_ID=
BOOTSTRAP_ADMINS=
CERTIFICATES=
STANDARD_CERTIFICATES=false
LOCK=
SKIPPED=
RENDER=false
RENDER_SITE=false
VERIFY=false
ALLOW_UNQUALIFIED_HOST=false
while [ $# -gt 0 ]; do
	case $1 in
	--version | --host | --email | --dns01-issuer | --github-client-id | --bootstrap-admins | --certificates | --lock | --skip-check)
		[ $# -ge 2 ] || usage
		case $1 in
		--version) VERSION=$2 ;;
		--dns01-issuer) DNS01_ISSUER=$2 ;;
		--host) HOST=$2 ;;
		--email) EMAIL=$2 ;;
		--github-client-id) GITHUB_CLIENT_ID=$2 ;;
		--bootstrap-admins) BOOTSTRAP_ADMINS=$2 ;;
		--certificates) CERTIFICATES=$2 ;;
		--lock) LOCK=$2 ;;
		--skip-check)
			matches "$2" 'dns|size|clock' || die "--skip-check takes dns, size or clock, not $2"
			SKIPPED="$SKIPPED $2"
			;;
		esac
		shift 2
		;;
	--workspace-subdomains)
		WORKSPACE_SUBDOMAINS=true
		shift
		;;
	--standard-certificates)
		STANDARD_CERTIFICATES=true
		shift
		;;
	--staging)
		STAGING=true
		shift
		;;
	--verify-kubelet)
		VERIFY=true
		shift
		;;
	--allow-unqualified-host)
		ALLOW_UNQUALIFIED_HOST=true
		shift
		;;
	--render-certificates)
		RENDER=true
		shift
		;;
	--render-site)
		RENDER_SITE=true
		shift
		;;
	*) usage ;;
	esac
done
skipped() { case " $SKIPPED " in *" $1 "*) return 0 ;; *) return 1 ;; esac }

# On a terminal, ask for what a first install lacks. A run without a terminal, such as a script or
# the release smoke, gets no question and stops below with the flag that it lacks.
ask() {
	printf '%s' "$1" >&2
	IFS= read -r answer || die "no answer: give the value as a flag instead"
}
if [ -t 0 ] && [ -t 2 ] && [ "$VERIFY" = false ] && [ "$RENDER" = false ]; then
	[ -n "$VERSION" ] || { ask "Release of these files, without the leading v (for example 1.0.0): " && VERSION=$answer; }
	[ -n "$HOST" ] || { ask "Host name of this instance (its DNS A record points to this host): " && HOST=$answer; }
	[ -n "$EMAIL" ] || [ -n "$CERTIFICATES" ] || { ask "E-mail address for the expiry notices of Let's Encrypt: " && EMAIL=$answer; }
	if [ ! -f "$(dirname "$0")/site.yaml" ]; then
		if [ -z "$GITHUB_CLIENT_ID" ]; then
			# GitHub documents no link that fills in the form of an OAuth app, so give the two values.
			printf '\nSign-in uses a GitHub OAuth app. Register one at https://github.com/settings/applications/new with:\n' >&2
			printf '  Homepage URL:               https://%s\n' "$HOST" >&2
			printf '  Authorization callback URL: https://%s/api/login/oauth2/code/github\n' "$HOST" >&2
			printf 'Then generate a client secret on the page of the app.\n' >&2
			ask "Client ID of the OAuth app: " && GITHUB_CLIENT_ID=$answer
		fi
		if [ -z "${GITHUB_OAUTH_CLIENT_SECRET:-}" ] && [ "$RENDER_SITE" = false ]; then
			# Echo goes off before the prompt, so that a fast paste after it is never shown, and it comes
			# back on every way out, Ctrl-C included.
			echo_state=$(stty -g)
			trap 'stty "$echo_state"; exit 130' HUP INT TERM
			stty -echo
			printf 'Client secret of the OAuth app (the terminal does not show it): ' >&2
			IFS= read -r GITHUB_OAUTH_CLIENT_SECRET || GITHUB_OAUTH_CLIENT_SECRET=
			stty "$echo_state"
			trap - HUP INT TERM
			printf '\n' >&2
			export GITHUB_OAUTH_CLIENT_SECRET
		fi
		if [ -z "$BOOTSTRAP_ADMINS" ]; then
			ask "Your GitHub login (you become the first instance admin): "
			matches "$answer" '[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?' || die "a GitHub login takes letters, digits and '-'"
			# The numeric ID, because a login that its owner gives up can be claimed by another person.
			id=$(curl -fsS --max-time 10 "https://api.github.com/users/$answer" 2>/dev/null | jq -r 'select(.type == "User") | .id // empty' 2>/dev/null || true)
			if matches "$id" '[1-9][0-9]*'; then
				BOOTSTRAP_ADMINS="github:$id"
			else
				warn "could not look up the numeric ID of $answer, so the admin list names the login: edit site.yaml to use the ID"
				BOOTSTRAP_ADMINS="github:@$answer"
			fi
		fi
	fi
fi

if [ "$VERIFY" = false ]; then
	LABEL='[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?'
	matches "$VERSION" '(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.(0|[1-9][0-9]*))?' || die "--version takes a release such as 0.95.0 or 1.0.0-rc.1"
	matches "$HOST" "($LABEL\\.)+[a-z]$LABEL" || die "--host takes a lowercase DNS name, such as hephaestus.example.com"
	if [ -z "$CERTIFICATES" ]; then
		matches "$EMAIL" "[A-Za-z0-9._%+-]+@($LABEL\\.)+[a-z]$LABEL" || die "--email takes an address, such as ops@example.com"
	else
		[ -f "$CERTIFICATES" ] || die "--certificates names no file: $CERTIFICATES"
	fi
	[ -z "$GITHUB_CLIENT_ID" ] || matches "$GITHUB_CLIENT_ID" '[A-Za-z0-9._-]{1,128}' ||
		die "--github-client-id takes letters, digits, '.', '_' and '-'"
	[ -z "$BOOTSTRAP_ADMINS" ] || matches "$BOOTSTRAP_ADMINS" '[a-z]+:@?[A-Za-z0-9._-]+(,[a-z]+:@?[A-Za-z0-9._-]+)*' ||
		die "--bootstrap-admins takes a list such as github:1234567,gitlab:@name"
	[ "$WORKSPACE_SUBDOMAINS" = false ] || {
		[ "${#HOST}" -le 189 ] || die "workspace subdomains need a host of at most 189 characters"
		[ -z "$CERTIFICATES" ] || die "--workspace-subdomains uses the standard certificates asset; configure a custom certificate in --certificates yourself"
		matches "$DNS01_ISSUER" "$LABEL" || die "workspace subdomains need --dns01-issuer with a ClusterIssuer name"
	}
	[ "$STANDARD_CERTIFICATES" = false ] || [ -z "$CERTIFICATES" ] || die "--standard-certificates cannot be combined with --certificates"
	[ -z "$DNS01_ISSUER" ] || [ "$WORKSPACE_SUBDOMAINS" = true ] || die "--dns01-issuer needs --workspace-subdomains"
	[ -z "$LOCK" ] || [ -f "$LOCK" ] || die "--lock names no file: $LOCK"
fi

HERE=$(cd "$(dirname "$0")" && pwd)
for file in hephaestus-keys.sh k3s-wait-condition.sh k3s-platform.yaml k3s-agent-sandbox.yaml k3s-containerd.toml.tmpl k3s-runtimeclass.yaml k3s-certificates.yaml k3s-config.yaml k3s-kubelet.yaml k3s-logind.conf k3s-journald.conf k3s-values.yaml; do
	[ -f "$HERE/$file" ] || die "$file is not beside this script: download, verify and unpack the k3s bundle of the release first"
done
# Existing values can enable wildcard routing. Require an explicit certificate choice on reruns
# instead of parsing YAML in shell or replacing that certificate with an implicit apex-only one.
if [ "$VERIFY" = false ] && [ "$RENDER" = false ] && [ "$RENDER_SITE" = false ] && [ -f "$HERE/site.yaml" ] && [ "$STANDARD_CERTIFICATES" = false ] && [ "$WORKSPACE_SUBDOMAINS" = false ] && [ -z "$CERTIFICATES" ]; then
	die "site.yaml exists: choose --standard-certificates, --workspace-subdomains --dns01-issuer NAME, or --certificates FILE"
fi
# An instance without a login provider starts, but nobody can sign in to it. So a first install needs
# the GitHub OAuth app, and a kept site.yaml must name a GitHub login provider or an enabled GitLab one.
# Slack and Outline only link accounts. Helm reads site.yaml for the install, so Helm also reads it
# here, through a chart whose only template names the provider: any YAML form that Helm accepts works.
login_provider() {
	command -v helm >/dev/null 2>&1 || die "helm is not installed"
	probe=$(mktemp -d)
	mkdir "$probe/templates"
	printf 'apiVersion: v2\nname: login-provider\nversion: 0.0.0\n' >"$probe/Chart.yaml"
	cat >"$probe/templates/provider.yaml" <<'TEMPLATE'
{{- $config := .Values.config | default dict }}
{{- $github := $config.github | default dict }}
{{- $gitlab := $config.gitlab | default dict }}
loginProvider: {{ if or $github.oauthClientId (and $gitlab.enabled $gitlab.oauthClientId) }}found{{ else }}none{{ end }}
TEMPLATE
	# site.yaml holds no secret, so the parse error of Helm can be shown as it is.
	if ! provider=$(helm template login-provider "$probe" -f "$1" 2>"$probe/error"); then
		problem=$(head -n 1 "$probe/error")
		rm -rf "$probe"
		die "Helm cannot read $1: $problem"
	fi
	rm -rf "$probe"
	case $provider in *"loginProvider: found"*) return 0 ;; *) return 1 ;; esac
}
if [ "$VERIFY" = false ] && [ "$RENDER" = false ]; then
	if [ -f "$HERE/site.yaml" ] && [ "$RENDER_SITE" = false ]; then
		login_provider "$HERE/site.yaml" ||
			die "site.yaml names no login provider, so nobody could sign in: set config.github.oauthClientId, or enable GitLab with config.gitlab.oauthClientId, in site.yaml (admin docs, First steps § 1), and run this again"
	elif [ ! -f "$HERE/site.yaml" ]; then
		[ -n "$GITHUB_CLIENT_ID" ] ||
			die "a first install needs --github-client-id: without a login provider, nobody can sign in"
		[ "$RENDER_SITE" = true ] || [ -n "${GITHUB_OAUTH_CLIENT_SECRET:-}" ] ||
			die "a first install needs the client secret of the GitHub OAuth app in GITHUB_OAUTH_CLIENT_SECRET"
	fi
fi
# The certificates file: the operator's host name and address, which passed the checks above and so
# hold no sed metacharacter, and the staging service of Let's Encrypt on request.
render_certificates() {
	if [ -n "$CERTIFICATES" ]; then
		cat "$CERTIFICATES"
		return
	fi
	server=acme-v02
	[ "$STAGING" = false ] || server=acme-staging-v02
	sed -e "s|hephaestus\\.example\\.com|$HOST|g" -e "s|acme@example\\.com|$EMAIL|g" \
		-e "s|acme-v02|$server|g" "$HERE/k3s-certificates.yaml" | {
		if [ "$WORKSPACE_SUBDOMAINS" = true ]; then
			sed -e "s|^    - $HOST\$|    - $HOST\\n    - \"*.$HOST\"|" \
				-e "s|^    name: gateway\$|    name: $DNS01_ISSUER|"
		else
			cat
		fi
	}
}
if [ "$RENDER" = true ]; then
	render_certificates
	exit 0
fi
# The site values: $1 and $2 are the address and port of the Kubernetes API, which only a running
# cluster knows. --render-site prints the file without them.
render_site() {
	printf 'host: %s\n' "$HOST"
	printf 'workspaceSubdomains:\n  enabled: %s\n' "$WORKSPACE_SUBDOMAINS"
	printf 'deploymentEnvironment: production\n'
	if [ -n "$1" ]; then
		printf 'networkPolicy:\n  kubernetesApi:\n    cidr: %s\n    port: %s\n' "$1" "$2"
	else
		printf '# networkPolicy.kubernetesApi: the installer reads it from the cluster\n'
	fi
	printf 'config:\n  gitCheckoutEnabled: true\n'
	if [ -n "$GITHUB_CLIENT_ID" ]; then printf '  github:\n    oauthClientId: %s\n' "$GITHUB_CLIENT_ID"; fi
	if [ -n "$BOOTSTRAP_ADMINS" ]; then
		printf '  auth:\n    bootstrapAdmins:\n'
		printf '%s\n' "$BOOTSTRAP_ADMINS" | tr ',' '\n' | while IFS= read -r admin; do
			printf '      - "%s"\n' "$admin"
		done
	fi
}
if [ "$RENDER_SITE" = true ]; then
	render_site "" ""
	exit 0
fi

TEMP=$(mktemp -d)
LISTENER=
cleanup() {
	if [ -n "$LISTENER" ]; then sudo kill "$LISTENER" 2>/dev/null || true; fi
	rm -rf "$TEMP"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Ubuntu 24.04 with its GA kernel is the declared baseline. Other families require an
# explicit rehearsal override; this gate does not substitute for the loaded host acceptance.
check_host_profile() {
	if matches "$1" 'Ubuntu 24\.04(\.[0-9]+)?( LTS)?' &&
		matches "$2" '6\.8\.[0-9]+([-+][A-Za-z0-9._+-]+)?'; then
		return
	fi
	[ "$ALLOW_UNQUALIFIED_HOST" = true ] ||
		die "unqualified host: $1 / $2; use --allow-unqualified-host only for an authorized rehearsal"
	warn "explicit host qualification override: $1 / $2; resource policy checks remain mandatory"
}

# Helm already ships with the installer. Use its YAML parser rather than parsing YAML in shell.
verify_kubelet() {
	policy="$TEMP/kubelet-policy"
	mkdir -p "$policy/templates"
	printf 'apiVersion: v2\nname: kubelet-policy\nversion: 0.0.0\n' >"$policy/Chart.yaml"
	printf '{{ toJson .Values }}\n' >"$policy/templates/policy.yaml"
	helm template kubelet-policy "$policy" --values "$HERE/k3s-kubelet.yaml" >"$TEMP/rendered.yaml"
	sed '/^#/d; /^---$/d' "$TEMP/rendered.yaml" >"$TEMP/wanted.json"
	# Ready can predate a restart. Allow configz and Node allocatable to converge together.
	verification_attempt=0
	until check_kubelet_snapshot; do
		verification_attempt=$((verification_attempt + 1))
		[ "$verification_attempt" -lt 31 ] || die "effective kubelet verification did not converge after 31 attempts"
		sleep 2
	done
}

check_kubelet_snapshot() {
	kubectl get nodes -o json >"$TEMP/nodes.json" || return 1
	[ "$(jq '.items | length' "$TEMP/nodes.json")" = 1 ] || die "the one-host policy requires exactly one node"
	node=$(jq -er '.items[0].metadata.name' "$TEMP/nodes.json") || return 1
	host_os=$(jq -r '.items[0].status.nodeInfo.osImage // "unknown"' "$TEMP/nodes.json") || return 1
	host_kernel=$(jq -r '.items[0].status.nodeInfo.kernelVersion // "unknown"' "$TEMP/nodes.json") || return 1
	check_host_profile "$host_os" "$host_kernel"
	kubectl get --raw "/api/v1/nodes/$node/proxy/configz" >"$TEMP/configz.json" || return 1
	# Configz adds defaults. Compare each configured leaf, including false and zero values.
	jq -e --slurpfile wanted "$TEMP/wanted.json" '
		.kubeletconfig as $live | $wanted[0] | del(.kind, .apiVersion) |
		[paths as $path | getpath($path) as $want |
		 select(($want | type) != "object" or ($want | length) == 0) |
		 ($live | getpath($path)) as $actual |
		 select(if ($want | type) == "object" then $actual != null and $actual != {}
		        else $want != $actual end) |
		 ($path | map(tostring) | join("."))] |
		if length == 0 then true else error("kubelet policy differs: " + join(", ")) end
	' "$TEMP/configz.json" >/dev/null || {
		warn "effective kubelet policy differs from k3s-kubelet.yaml; check k3s startup flags and drop-ins"
		return 1
	}
	jq -e --slurpfile wanted "$TEMP/wanted.json" '
		def quantity:
		  tostring | capture("^(?<n>[0-9]+(?:\\.[0-9]+)?)(?<u>Ki|Mi|Gi|m)?$") |
		  (.n | tonumber) * ({"Ki":1024,"Mi":1048576,"Gi":1073741824,"m":0.001,"":1}[.u // ""]);
		$wanted[0] as $w | .items[0].status as $s |
		(($s.capacity.memory | quantity) - ($w.kubeReserved.memory | quantity) -
		 ($w.systemReserved.memory | quantity) - ($w.evictionHard["memory.available"] | quantity)) as $memory |
		(($s.capacity.cpu | quantity) - ($w.kubeReserved.cpu | quantity) -
		 ($w.systemReserved.cpu | quantity)) as $cpu |
		if ($s.allocatable.memory | quantity) == $memory and
		   (($s.allocatable.cpu | quantity) * 1000 | round) == ($cpu * 1000 | round)
		then true else error("node allocatable differs from capacity less reservations and hard eviction floor") end
	' "$TEMP/nodes.json" >/dev/null || {
		warn "node allocatable does not enforce the configured budget"
		return 1
	}
	say "Effective kubelet policy and node allocatable match k3s-kubelet.yaml"
}

if [ "$VERIFY" = true ]; then
	for tool in helm jq kubectl; do
		command -v "$tool" >/dev/null 2>&1 || die "$tool is not on PATH: install it first"
	done
	verify_kubelet
	exit 0
fi
for tool in curl jq openssl cosign helm sha256sum sha512sum bzip2 getent ss sudo; do
	command -v "$tool" >/dev/null 2>&1 || die "$tool is not on PATH: install it first"
done
case $(helm version --template '{{.Version}}' 2>/dev/null) in
v4.*) ;;
*) die "Helm 4 is required for --rollback-on-failure and --wait=watcher; this is $(helm version --short 2>/dev/null)" ;;
esac
# Ask for the sudo password now, in the foreground: a later sudo runs beside the port 80 check.
sudo -v || die "this script needs sudo rights"

# An immutable release can be reapplied, but a different release can run one-way migrations.
# Stop, restore-point verification and migration are separate operator phases, not a rollout.
if command -v k3s >/dev/null 2>&1 || [ -f "$HERE/kubeconfig" ] || sudo test -f /etc/rancher/k3s/k3s.yaml; then
	releases=$(sudo "$(command -v helm)" --kubeconfig /etc/rancher/k3s/k3s.yaml \
		list --namespace "$NAMESPACE" --filter '^hephaestus$' --output json) ||
		die "cannot read the installed release; restore cluster access before retrying; do not run an older release against a changed database"
	printf '%s\n' "$releases" | jq -e --arg version "$VERSION" '
		type == "array" and (length == 0 or (length == 1 and
			.[0].status == "deployed" and .[0].chart == ("hephaestus-" + $version) and
			.[0].app_version == $version))
	' >/dev/null 2>&1 || die "release changes and failed or pending releases require Stopped schema upgrades: https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades; keep roles stopped after a migration failure; restore the matching backup before returning to the old release"
fi

K3S_INSTALLED=false
if command -v k3s >/dev/null 2>&1 && systemctl is-active --quiet k3s; then K3S_INSTALLED=true; fi

say "Checking the host"
[ "$(awk 'NR > 1 { count++ } END { print count + 0 }' /proc/swaps)" = 0 ] ||
	die "swap is active: disable host swap and its boot configuration before installing"
case $(uname -m) in
x86_64 | aarch64) ;;
*) die "k3s and the release images need x86_64 or aarch64, not $(uname -m)" ;;
esac
# shellcheck disable=SC1091 # The file of the host, not of this script.
os=$(. /etc/os-release && printf '%s %s' "$NAME" "$VERSION_ID")
check_host_profile "$os" "$(uname -r)"
memory=$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)
if skipped size; then
	warn "the size check is skipped"
else
	[ "$memory" -ge "$SMALL_MEMORY_KIB" ] ||
		die "this host has $((memory / 1048576)) GiB of memory; the small profile needs a host of 16 GiB"
	[ "$(nproc)" -ge "$MIN_CPUS" ] || die "this host has $(nproc) CPUs; the small profile needs 4"
	free=$(df -Pk /var | awk 'NR == 2 { print $4 }')
	[ "$free" -ge 104857600 ] || warn "/var has $((free / 1048576)) GiB free; the small profile counts on 100 GiB for volumes, images and caches"
fi
if skipped clock; then
	warn "the clock check is skipped"
else
	[ "$(timedatectl show --property=NTPSynchronized --value 2>/dev/null)" = yes ] ||
		die "the clock is not synchronized by NTP: run sudo timedatectl set-ntp true, wait for the sync, and run this again"
fi
# The host's own firewall must let ports 80 and 443 in. A cloud firewall or security group is the
# operator's to open: nothing on this host can see it.
# A port rule such as 80/tcp or 80,443/tcp, or a web server's application profile.
ufw_allows() {
	sudo ufw status | awk -v port="$1" '
		/ ALLOW/ {
			rule = substr($0, 1, index($0, " ALLOW") - 1)
			sub(/[ \t]+$/, "", rule)
			sub(/ \(v6\)$/, "", rule)
			if (rule == "Nginx Full" || rule == "Apache Full" || rule == "WWW Full") {
				if (port == 80 || port == 443) found = 1
			} else if (rule == "Nginx HTTP" || rule == "Apache" || rule == "WWW") {
				if (port == 80) found = 1
			} else if (rule == "Nginx HTTPS" || rule == "Apache Secure" || rule == "WWW Secure") {
				if (port == 443) found = 1
			} else {
				split(rule, parts, "/")
				if (parts[2] == "" || parts[2] == "tcp") {
					count = split(parts[1], ports, ",")
					for (i = 1; i <= count; i++) if (ports[i] == port) found = 1
				}
			}
		}
		END { exit !found }'
}
if command -v ufw >/dev/null 2>&1 && sudo ufw status | grep -q '^Status: active'; then
	for port in 80 443; do
		ufw_allows "$port" ||
			die "ufw is active and allows no $port/tcp: no port rule and no Nginx, Apache or WWW profile, the profiles that this check reads. Run: sudo ufw allow 80,443/tcp && sudo ufw allow from 10.42.0.0/16 && sudo ufw allow from 10.43.0.0/16"
	done
fi
if systemctl is-active --quiet firewalld 2>/dev/null; then
	interface=$(ip route show default 2>/dev/null | awk '{ print $5; exit }')
	# An interface without a zone prints "no zone" and succeeds; the default zone applies to it then.
	zone=$(sudo firewall-cmd --get-zone-of-interface="$interface" 2>/dev/null || true)
	case $zone in "" | "no zone") zone=$(sudo firewall-cmd --get-default-zone) ;; esac
	for pair in http:80 https:443; do
		sudo firewall-cmd --zone="$zone" --query-service="${pair%%:*}" >/dev/null 2>&1 ||
			sudo firewall-cmd --zone="$zone" --query-port="${pair#*:}/tcp" >/dev/null 2>&1 ||
			die "firewalld's zone $zone does not allow ${pair%%:*}. Run: sudo firewall-cmd --permanent --zone=$zone --add-service=http --add-service=https && sudo firewall-cmd --reload"
	done
fi
if [ "$K3S_INSTALLED" = false ]; then
	busy=$(ss -Hltn '( sport = :80 or sport = :443 )' 2>/dev/null || true)
	[ -z "$busy" ] || die "another program listens on port 80 or 443; stop it, because Traefik takes both ports:
$busy"
fi
if skipped dns; then
	warn "the DNS and reachability check is skipped"
else
	getent ahostsv4 "$HOST" >/dev/null || die "$HOST does not resolve: add a DNS A record for it that points to this host"
	if [ "$K3S_INSTALLED" = false ]; then
		# Let's Encrypt asks for http://<host>/ from the internet. A short listener on port 80 serves a
		# random token, and this host asks for it through the host name and the firewall.
		command -v python3 >/dev/null 2>&1 || die "python3 is not on PATH; it serves the reachability check. Install it, or run again with --skip-check dns"
		token=$(openssl rand -hex 16)
		mkdir -p "$TEMP/web/.well-known/hephaestus"
		printf '%s' "$token" >"$TEMP/web/.well-known/hephaestus/$token"
		chmod -R a+rX "$TEMP"
		sudo python3 -m http.server 80 --bind 0.0.0.0 --directory "$TEMP/web" >/dev/null 2>&1 &
		LISTENER=$!
		answer=
		for _ in 1 2 3 4 5; do
			sleep 2
			answer=$(curl -fsS --max-time 10 "http://$HOST/.well-known/hephaestus/$token" 2>/dev/null || true)
			[ "$answer" != "$token" ] || break
		done
		sudo kill "$LISTENER" 2>/dev/null || true
		LISTENER=
		[ "$answer" = "$token" ] || die "http://$HOST/ does not reach this host on port 80. Check that the DNS record names this host's public address and that the firewall admits port 80 from the internet. If this network cannot reach its own public address, run again with --skip-check dns"
	fi
fi

# shellcheck disable=SC2046 # The pin line splits into its words on purpose.
set -- $(grep '^# pin: k3s ' "$HERE/k3s-platform.yaml")
K3S_VERSION=${4:-}
K3S_INSTALL_SHA256=${5:-}
matches "$K3S_VERSION" 'v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+' || die "k3s-platform.yaml has no k3s pin"
matches "$K3S_INSTALL_SHA256" '[a-f0-9]{64}' || die "k3s-platform.yaml has no SHA-256 for the k3s installer"

# gVisor from the pinned release archive, for this host's architecture. A host whose runsc reports
# the pinned release, beside its required sidecars, keeps it. runsc goes last, so an interrupted
# install leaves no runsc of the new release.
install_gvisor() {
	# shellcheck disable=SC2046 # The pin line splits into its words on purpose.
	set -- $(grep '^# pin: gvisor ' "$HERE/k3s-containerd.toml.tmpl")
	gvisor_release=${4:-}
	case $(uname -m) in
	x86_64) gvisor_arch=x86_64 gvisor_sha512=${5:-} ;;
	aarch64 | arm64) gvisor_arch=aarch64 gvisor_sha512=${6:-} ;;
	*) die "gVisor has no release for $(uname -m)" ;;
	esac
	matches "$gvisor_release" '[0-9]{8}\.[0-9]+' || die "k3s-containerd.toml.tmpl has no gVisor pin"
	matches "$gvisor_sha512" '[a-f0-9]{128}' || die "k3s-containerd.toml.tmpl has no SHA-512 for gVisor on $gvisor_arch"
	# runsc starts no sandbox without these two sidecars of gvisor-bin: the Sentry it boots and the
	# /proc unmounter its gofer runs. A host that lost either installs the release again.
	if /usr/local/bin/runsc --version 2>/dev/null | head -n 1 | grep -qF "release-$gvisor_release" &&
		[ -x /usr/local/bin/gvisor-bin/gvisor_sentry ] && [ -x /usr/local/bin/gvisor-bin/gvisor-proc-umounter ]; then
		return
	fi
	say "Installing gVisor $gvisor_release"
	curl -fsSL --proto '=https' --retry 5 -o "$TEMP/gvisor.tar.bz2" \
		"https://storage.googleapis.com/gvisor/releases/release/$gvisor_release/$gvisor_arch/gvisor.tar.bz2"
	printf '%s  %s\n' "$gvisor_sha512" "$TEMP/gvisor.tar.bz2" | sha512sum -c - >/dev/null ||
		die "the gVisor archive does not match the pinned SHA-512"
	mkdir "$TEMP/gvisor"
	tar -xjf "$TEMP/gvisor.tar.bz2" -C "$TEMP/gvisor"
	sudo rm -rf /usr/local/bin/gvisor-bin
	sudo install -d -m 0755 /usr/local/bin/gvisor-bin
	sudo install -m 0755 "$TEMP/gvisor/gvisor-bin/"* /usr/local/bin/gvisor-bin/
	sudo install -m 0755 "$TEMP/gvisor/containerd-shim-runsc-v1" /usr/local/bin/
	sudo install -m 0755 "$TEMP/gvisor/runsc" /usr/local/bin/
}

say "Placing the host and platform files"
sudo install -D -m 0644 "$HERE/k3s-logind.conf" /etc/systemd/logind.conf.d/zz-hephaestus.conf
sudo systemctl kill --signal=SIGHUP systemd-logind
if ! sudo cmp -s "$HERE/k3s-journald.conf" /etc/systemd/journald.conf.d/zz-hephaestus.conf; then
	sudo install -D -m 0644 "$HERE/k3s-journald.conf" /etc/systemd/journald.conf.d/zz-hephaestus.conf
	sudo systemctl restart systemd-journald
fi
# k3s reads these two files only when it starts.
K3S_CONFIG_CHANGED=false
sudo cmp -s "$HERE/k3s-config.yaml" /etc/rancher/k3s/config.yaml || K3S_CONFIG_CHANGED=true
sudo cmp -s "$HERE/k3s-kubelet.yaml" /etc/rancher/k3s/kubelet.yaml || K3S_CONFIG_CHANGED=true
sudo install -D -m 0600 "$HERE/k3s-config.yaml" /etc/rancher/k3s/config.yaml
sudo install -D -m 0600 "$HERE/k3s-kubelet.yaml" /etc/rancher/k3s/kubelet.yaml
install_gvisor
CONTAINERD_TEMPLATE=/var/lib/rancher/k3s/agent/etc/containerd/config-v3.toml.tmpl
sudo cmp -s "$HERE/k3s-containerd.toml.tmpl" "$CONTAINERD_TEMPLATE" || K3S_CONFIG_CHANGED=true
sudo install -D -m 0644 "$HERE/k3s-containerd.toml.tmpl" "$CONTAINERD_TEMPLATE"
sudo install -D -m 0600 "$HERE/k3s-runtimeclass.yaml" "$MANIFESTS/k3s-runtimeclass.yaml"
sudo install -D -m 0600 "$HERE/k3s-platform.yaml" "$MANIFESTS/k3s-platform.yaml"
sudo install -D -m 0600 "$HERE/k3s-agent-sandbox.yaml" "$MANIFESTS/k3s-agent-sandbox.yaml"
render_certificates >"$TEMP/certificates.yaml"
sudo install -D -m 0600 "$TEMP/certificates.yaml" "$MANIFESTS/hephaestus-certificates.yaml"

if [ "$K3S_INSTALLED" = true ] && k3s --version | head -n 1 | grep -qF " $K3S_VERSION "; then
	if [ "$K3S_CONFIG_CHANGED" = true ]; then
		say "Restarting k3s $K3S_VERSION for its changed configuration"
		sudo systemctl restart k3s
	else
		say "k3s $K3S_VERSION runs already"
	fi
else
	say "Installing k3s $K3S_VERSION"
	encoded=$(printf '%s' "$K3S_VERSION" | sed 's/+/%2B/')
	curl -fsSL --proto '=https' --retry 5 -o "$TEMP/install-k3s.sh" "https://raw.githubusercontent.com/k3s-io/k3s/$encoded/install.sh"
	printf '%s  %s\n' "$K3S_INSTALL_SHA256" "$TEMP/install-k3s.sh" | sha256sum -c - >/dev/null ||
		die "the k3s installer does not match the pinned SHA-256"
	INSTALL_K3S_VERSION=$K3S_VERSION sh "$TEMP/install-k3s.sh"
fi
KUBECONFIG="$HERE/kubeconfig"
export KUBECONFIG
sudo install -m 0600 -o "$(id -un)" /etc/rancher/k3s/k3s.yaml "$KUBECONFIG"
tries=0
until kubectl get nodes -o name 2>/dev/null | grep -q .; do
	tries=$((tries + 1))
	[ "$tries" -lt 150 ] || die "k3s registered no node in 5 minutes: sudo journalctl -u k3s shows why"
	sleep 2
done
kubectl wait --for=condition=Ready node --all --timeout=5m >/dev/null
verify_kubelet

say "Creating the namespaces"
for namespace in "$NAMESPACE" "$SANDBOXES"; do
	kubectl create namespace "$namespace" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
	kubectl label namespace "$namespace" pod-security.kubernetes.io/enforce=restricted --overwrite >/dev/null
done

say "Keeping the release Secret"
# hephaestus-keys.sh adds only the keys that the Secret lacks, and stops when the PostgreSQL volume
# exists without its login or an encryption key. It prints key names, never a value.
keys=$(sh "$HERE/hephaestus-keys.sh" --namespace "$NAMESPACE" --secret "$SECRET") ||
	die "the release Secret was not kept: see the message above"
printf '%s\n' "$keys" | sed 's/^/    /'
oauth_changed=false
if printf '%s\n' "$keys" | grep -qx 'Changed GITHUB_OAUTH_CLIENT_SECRET'; then oauth_changed=true; fi

say "Waiting for the agent-sandbox controller"
# k3s records a manifest's SHA-256 on its Addon once it has applied that content, so a rerun with a
# new pin waits for the new controller and definitions, not for the ones that run already.
sandbox_sum=$(sha256sum "$HERE/k3s-agent-sandbox.yaml" | cut -d' ' -f1)
tries=0
until [ "$(kubectl -n kube-system get addon k3s-agent-sandbox -o jsonpath='{.spec.checksum}' 2>/dev/null || true)" = "$sandbox_sum" ]; do
	tries=$((tries + 1))
	[ "$tries" -lt 300 ] || die "k3s did not apply k3s-agent-sandbox.yaml in 10 minutes: kubectl -n kube-system describe addon k3s-agent-sandbox shows why"
	sleep 2
done
for crd in sandboxes.agents.x-k8s.io sandboxtemplates.extensions.agents.x-k8s.io \
	sandboxclaims.extensions.agents.x-k8s.io sandboxwarmpools.extensions.agents.x-k8s.io; do
	sh "$HERE/k3s-wait-condition.sh" - "crd/$crd" Established 120
done
kubectl -n agent-sandbox-system rollout status deployment/agent-sandbox-controller --timeout=5m >/dev/null ||
	die "the agent-sandbox controller did not start in 5 minutes: kubectl -n agent-sandbox-system describe pods shows why"

say "Waiting for cert-manager to issue the certificates"
kubectl wait --for=create crd/certificates.cert-manager.io --timeout=10m >/dev/null
sh "$HERE/k3s-wait-condition.sh" - crd/certificates.cert-manager.io Established 120
if [ "$WORKSPACE_SUBDOMAINS" = true ]; then
	kubectl wait --for=create "clusterissuer/$DNS01_ISSUER" --timeout=5m >/dev/null ||
		die "the DNS-01 ClusterIssuer $DNS01_ISSUER does not exist: install the issuer and its private credential first"
	sh "$HERE/k3s-wait-condition.sh" - "clusterissuer/$DNS01_ISSUER" Ready 300 ||
		die "the DNS-01 ClusterIssuer $DNS01_ISSUER is not ready: check its account and credential"
fi
kubectl -n "$NAMESPACE" wait --for=create certificate/hephaestus-postgresql-tls --timeout=10m >/dev/null
sh "$HERE/k3s-wait-condition.sh" "$NAMESPACE" certificate/hephaestus-postgresql-tls Ready 600 ||
	die "cert-manager issued no PostgreSQL certificate in 10 minutes: kubectl -n $NAMESPACE describe certificate hephaestus-postgresql-tls shows why"
ready=$(kubectl -n "$GATEWAY_NAMESPACE" get certificate hephaestus-tls --ignore-not-found \
	-o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
if [ -z "$CERTIFICATES" ] && [ "$WORKSPACE_SUBDOMAINS" = false ] && [ "$ready" != True ]; then
	say "Waiting up to 10 minutes for Let's Encrypt, which must reach this host on port 80 from the internet"
	echo "    Without that path, stop now, and run this again with --certificates and your own certificate."
fi
kubectl -n "$GATEWAY_NAMESPACE" wait --for=create certificate/hephaestus-tls --timeout=10m >/dev/null
sh "$HERE/k3s-wait-condition.sh" "$GATEWAY_NAMESPACE" certificate/hephaestus-tls Ready 600 ||
	die "cert-manager issued no certificate for $HOST in 10 minutes: kubectl describe challenges --all-namespaces shows why"

if [ -f "$HERE/site.yaml" ]; then
	say "Keeping site.yaml"
	if [ -n "$GITHUB_CLIENT_ID$BOOTSTRAP_ADMINS$DNS01_ISSUER" ]; then
		warn "site.yaml exists, so --github-client-id, --bootstrap-admins and --workspace-subdomains change nothing: edit site.yaml and run this again"
	fi
else
	say "Writing site.yaml"
	address=$(kubectl get endpointslices -n default -l kubernetes.io/service-name=kubernetes \
		-o jsonpath='{.items[0].endpoints[0].addresses[0]}')
	port=$(kubectl get endpointslices -n default -l kubernetes.io/service-name=kubernetes \
		-o jsonpath='{.items[0].ports[0].port}')
	case $address in *:*) cidr="$address/128" ;; *) cidr="$address/32" ;; esac
	render_site "$cidr" "$port" >"$HERE/site.yaml"
fi

say "Verifying the release chart"
# The commands of docs/admin/kubernetes.mdx § Verify the release chart.
if [ -z "$LOCK" ]; then
	LOCK="$TEMP/release-v$VERSION.chart.json"
	curl -fsSL --proto '=https' --retry 5 -o "$LOCK" \
		"https://github.com/$RELEASE_REPOSITORY/releases/download/v$VERSION/release-v$VERSION.chart.json"
fi
digest=$(jq -er .chart.digest "$LOCK")
matches "$digest" 'sha256:[a-f0-9]{64}' || die "the joint lock names no chart digest"
cosign verify "$CHART_REPOSITORY@$digest" --certificate-identity "$SIGNER" --certificate-oidc-issuer "$ISSUER" >/dev/null
helm pull "oci://$CHART_REPOSITORY@$digest" --untar --untardir "$TEMP/chart"
helm show chart "$TEMP/chart/hephaestus" | grep -qx "version: $VERSION" || die "the signed chart is not the chart of $VERSION"
rm -rf "$HERE/chart-$VERSION"
mkdir "$HERE/chart-$VERSION"
mv "$TEMP/chart/hephaestus" "$HERE/chart-$VERSION/hephaestus"

say "Installing the release"
profiles="$HERE/chart-$VERSION/hephaestus/profiles"
set -- -f "$profiles/small.yaml"
# The guard permits only a first install or the same immutable release. Rollback cannot start an older release.
helm upgrade --install hephaestus "$HERE/chart-$VERSION/hephaestus" -n "$NAMESPACE" \
	"$@" -f "$HERE/k3s-values.yaml" -f "$HERE/site.yaml" \
	--rollback-on-failure --wait=watcher --wait-for-jobs --timeout 20m
if [ "$oauth_changed" = true ]; then
	# Startup seeds a missing provider. An existing provider keeps its database credential.
	kubectl -n "$NAMESPACE" rollout restart deployment/hephaestus-server >/dev/null
fi

say "Hephaestus $VERSION runs at https://$HOST"
cat <<EOF
- For kubectl and helm on this host: export KUBECONFIG="$KUBECONFIG"

Back up the encryption keys now. Hephaestus cannot make them again.
If you lose them, sign-in stops, and the stored provider secrets, AI keys and integration
credentials are lost for good. A new install cannot read them.
This command writes the keys to a new file that only you can read. It prints no key:

  sh "$HERE/hephaestus-keys.sh" --export-keys hephaestus-keys-\$(date +%F).env

The file is not encrypted: put it in a password manager or encrypt it, keep it with site.yaml
off this host, then delete it here. After each key change, export a new copy.
To prove that a kept copy still matches the Secret, run it with --check-keys and that file.
The admin docs page "Secret custody and rotation" gives the full rule.
EOF
if [ "$STAGING" = true ]; then
	echo "- The certificate comes from the Let's Encrypt staging service. Run this again without --staging, then delete the Secret $GATEWAY_NAMESPACE/hephaestus-tls."
fi
