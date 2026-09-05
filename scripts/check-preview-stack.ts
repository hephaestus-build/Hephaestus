/**
 * Asserts that the preview stack cannot leave its sandbox, and that the seed policy which strips
 * staging out of a clone verifies every store it touches.
 *
 * Coolify re-reads docker/preview/compose.app.yaml from the commit it deploys, so a pull request's
 * own copy of that file defines its stack. The preview controller refuses to deploy a head that
 * changes anything under docker/preview/; this is the other half of that rule, and it says what
 * "unchanged" has to keep meaning.
 */
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";

import { readComposeServices } from "./check-env-roles.ts";
import { isRecord } from "./lib/json.ts";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const COMPOSE_FILE = "docker/preview/compose.app.yaml";
const REFERENCE_FILE = "docker/compose.app.yaml";
/** Images this repository builds, addressed by commit rather than digest. */
const OWN_IMAGE_PREFIX = "ghcr.io/hephaestus-build/";

/**
 * Reference variables a preview deliberately does not set. Inheriting the reference instead of
 * restating it was tried and rejected — Compose merges per key, so integrations arrive switched on
 * (ADR 0035, option 6). This list is what keeps restating from being a silent omission: a variable
 * added to the reference and not considered here fails the build.
 */
const DELIBERATELY_OMITTED = new Set([
	"GITLAB_DEFAULT_SERVER_URL",
	"GITLAB_OAUTH_BASE_URL",
	"GITLAB_OAUTH_CLIENT_ID",
	"GITLAB_OAUTH_CLIENT_SECRET",
	"GITLAB_OAUTH_DISPLAY_NAME",
	"HEPHAESTUS_INTEGRATION_OUTLINE_ALLOWED_ORIGINS",
	"HEPHAESTUS_INTEGRATION_SLACK_CLIENT_ID",
	"HEPHAESTUS_INTEGRATION_SLACK_CLIENT_SECRET",
	"HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI",
	"OUTLINE_OAUTH_BASE_URL",
	"OUTLINE_OAUTH_CLIENT_ID",
	"OUTLINE_OAUTH_CLIENT_SECRET",
	"OUTLINE_OAUTH_DISPLAY_NAME",
	"GH_APP_INSTALLATION_URL",
	// GitHub App authentication is disabled, so no private key is mounted.
	"GH_APP_PRIVATE_KEY_LOCATION",
	// The agent sandbox is off, so nothing reads its runtime, limits or image override.
	"HEPHAESTUS_AGENT_IMAGE_REFERENCE",
	"HEPHAESTUS_FABRIC_GC_RETENTION_DAYS",
	"HEPHAESTUS_FABRIC_ROOT",
	"HEPHAESTUS_LLM_DISPLAY_CURRENCY",
	"HEPHAESTUS_LLM_EGRESS_ALLOW_LOOPBACK",
	"HEPHAESTUS_LLM_FX_DAILY_URL",
	// A preview is torn down long before any spend-ledger row reaches the retention window.
	"HEPHAESTUS_LLM_USAGE_RETENTION",
	"SANDBOX_API_MAX_REQUEST_BYTES",
	"SANDBOX_API_PORT",
	"SANDBOX_API_REQUESTS_PER_MINUTE",
	"SANDBOX_DOCKER_CONTAINER_RUNTIME",
	"SANDBOX_CPUS",
	"SANDBOX_DOCKER_HOST",
	"SANDBOX_DOCKER_TLS_VERIFY",
	"SANDBOX_DOCKER_CERT_PATH",
	"SANDBOX_DOCKER_APP_SERVER_CONTAINER_ID",
	"SANDBOX_DOCKER_CLI",
	"SANDBOX_MAX_CONCURRENT",
	"SANDBOX_MEMORY_BYTES",
	"HEPHAESTUS_WORKER_HUB_TOKEN_REGISTRATION_TOKEN",
	// Sync and the leaderboard schedule are disabled outright, so their tuning knobs are inert.
	"LEADERBOARD_SCHEDULE_DAY",
	"LEADERBOARD_SCHEDULE_TIME",
	"MONITORING_BACKFILL_BATCH_SIZE",
	"MONITORING_BACKFILL_INTERVAL_SECONDS",
	"MONITORING_BACKFILL_RATE_LIMIT_THRESHOLD",
	"MONITORING_SYNC_COOLDOWN_IN_MINUTES",
	"MONITORING_TIMEFRAME",
	// Seeding a preview disables every practice-review trigger, agent binding and sweep schedule, so
	// review scheduling and the ledger that reaps the signals it refuses have no consumer here.
	"PRACTICE_REVIEW_BACKFILL_BATCH_SIZE",
	"PRACTICE_REVIEW_BACKFILL_COST_HISTORY_WINDOW",
	"PRACTICE_REVIEW_BACKFILL_MAX_ARTIFACTS",
	"PRACTICE_REVIEW_BACKFILL_MAX_WINDOW",
	"PRACTICE_REVIEW_COOLDOWN_MINUTES",
	"PRACTICE_REVIEW_DELIVER_TO_MERGED",
	"PRACTICE_REVIEW_MAX_REQUESTS_PER_REQUESTER_PER_HOUR",
	"PRACTICE_REVIEW_PROGRESS_FOOTER",
	"PRACTICE_REVIEW_REACTION_SUPPRESSION",
	"SIGNAL_LEDGER_PENDING_LAPSE_AFTER",
	"SIGNAL_LEDGER_PENDING_RETRY_AFTER",
	"SIGNAL_LEDGER_SWEEP_BATCH_SIZE",
	// A preview seeds no admin out of band; HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS covers it.
	"HEPHAESTUS_AUTH_BOOTSTRAP_TOKEN",
]);

export function findEnvDrift(referenceText: string, previewText: string): string[] {
	const reference = readComposeServices(referenceText).get("application-server");
	const preview = readComposeServices(previewText).get("appserver");
	if (!reference || !preview)
		return ["could not read the reference or preview application service"];
	return [...reference.flags.keys()]
		.filter((key) => !preview.flags.has(key) && !DELIBERATELY_OMITTED.has(key))
		.toSorted()
		.map(
			(key) =>
				`${REFERENCE_FILE} sets ${key} but ${COMPOSE_FILE} neither sets it nor records it as deliberately omitted`,
		);
}

/** An omission entry outliving the variable it excuses would silently mask a future divergence. */
export function findStaleOmissions(referenceText: string): string[] {
	const reference = readComposeServices(referenceText).get("application-server");
	if (!reference) return ["could not read the reference application service"];
	return [...DELIBERATELY_OMITTED]
		.filter((key) => !reference.flags.has(key))
		.toSorted()
		.map(
			(key) => `${key} is recorded as deliberately omitted but ${REFERENCE_FILE} no longer sets it`,
		);
}

/** The heredoc the seed loader pipes into psql, and the query that decides whether it took effect. */
const SEED_POLICY = /<<'SQL'\n([\s\S]*?)\n[ \t]*SQL\n/;
const SEED_VERIFICATION = /LIVE=\$\$\(psql[^\n]*-c "([\s\S]*?)"\)/;
const CHANGED_TABLE = /\b(?:UPDATE|DELETE FROM)[\s\n]+([a-z_]+)/g;
const COUNTED_TABLE = /\bFROM[\s\n]+([a-z_]+)/g;

function tablesIn(sql: string, pattern: RegExp): Set<string> {
	return new Set([...sql.matchAll(pattern)].flatMap((match) => match[1] ?? []));
}

/**
 * A clone of staging earns its seed marker by being counted, not by psql's exit status — every
 * statement in the policy succeeds when it matches nothing. The policy and the count are written far
 * apart in the same shell block, so a store the policy clears and the count forgets turns a policy
 * that silently did not apply into a live preview holding staging's secrets, and a store the count
 * watches and the policy never clears leaves every preview un-booted.
 */
export function findSeedPolicyGaps(previewText: string): string[] {
	const policy = SEED_POLICY.exec(previewText)?.[1];
	if (policy === undefined) return ["the seed loader applies no SQL policy"];
	const verification = SEED_VERIFICATION.exec(previewText)?.[1];
	if (verification === undefined) {
		return ["the seed loader marks a clone seeded without verifying its policy"];
	}
	const changed = tablesIn(policy, CHANGED_TABLE);
	const counted = tablesIn(verification, COUNTED_TABLE);
	return [
		...[...changed.difference(counted)]
			.toSorted()
			.map((table) => `the policy changes ${table} but the verification query never counts it`),
		...[...counted.difference(changed)]
			.toSorted()
			.map((table) => `the verification query counts ${table} but the policy never changes it`),
	];
}

/** Only has to let Compose interpolate; Coolify supplies the real values per pull request. */
const RENDER_ENV: Record<string, string> = {
	SOURCE_COMMIT: "a".repeat(40),
	POSTGRES_PASSWORD: "ci-not-a-real-password",
	PREVIEW_SEED_SOURCE_PASSWORD: "ci-not-a-real-seed-password",
	HEPHAESTUS_AUTH_STATE_COOKIE_KEY: "ci-not-a-real-state-cookie-key",
	HEPHAESTUS_SECURITY_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef",
	WEBHOOK_SECRET: "ci-not-a-real-webhook-secret-0123456789",
	HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY: "0123456789abcdef0123456789abcdef",
	NATS_USERNAME: "ci-not-a-real-nats-user",
	NATS_PASSWORD: "ci-not-a-real-nats-password",
	HEPHAESTUS_TRUSTED_PROXIES: "172.(1[6-9]|2[0-9]|3[01]).[0-9]{1,3}.[0-9]{1,3}",
	SERVICE_FQDN_WEBAPP: "pr1.example.com",
	SERVICE_FQDN_APPSERVER: "pr1.api.example.com",
};

/**
 * Capabilities a service may add back after `cap_drop: ALL`. Each set is the minimum found by running
 * the image with less; the application server needs none.
 */
const ALLOWED_CAPABILITIES: Record<string, readonly string[]> = {
	postgres: ["CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID"],
	webapp: ["CHOWN", "SETGID", "SETUID"],
};

/** Values the server refuses to start without: it validates them before the context is built, so an
 * empty one here is a preview that restart-loops rather than a preview that misbehaves quietly. */
const REQUIRED_NON_EMPTY = ["HEPHAESTUS_TRUSTED_PROXIES", "WEBHOOK_SECRET"];

/**
 * Switches that keep a preview from reaching anything outside itself. A rename or a typo would not
 * fail at boot — Spring would fall back to its own default, several of which are on — it would quietly
 * produce a preview that syncs GitHub and sends notifications.
 */
export const REQUIRED_SWITCHES: Record<string, string> = {
	AGENT_ENABLED: "false",
	GIT_CHECKOUT_ENABLED: "false",
	GITLAB_ENABLED: "false",
	HEPHAESTUS_AGENT_IMAGE_REQUIRE_DIGEST: "false",
	HEPHAESTUS_INTEGRATION_OUTLINE_ENABLED: "false",
	HEPHAESTUS_INTEGRATION_SLACK_ENABLED: "false",
	HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED: "false",
	HEPHAESTUS_RUNTIME_WORKER_ENABLED: "false",
	LEADERBOARD_NOTIFICATION_ENABLED: "false",
	MONITORING_BACKFILL_ENABLED: "false",
	MONITORING_RUN_ON_STARTUP: "false",
	MONITORING_SYNC_CRON: "-",
};

function records(value: unknown): [string, Record<string, unknown>][] {
	if (!isRecord(value)) return [];
	return Object.entries(value).filter((entry): entry is [string, Record<string, unknown>] =>
		isRecord(entry[1]),
	);
}

export function findViolations(stack: unknown): string[] {
	if (!isRecord(stack)) return ["the rendered stack is not an object"];
	const violations: string[] = [];
	const services = records(stack.services);
	if (services.length === 0) violations.push("the rendered stack declares no services");

	for (const [name, service] of services) {
		const mounts = Array.isArray(service.volumes) ? service.volumes : [];
		for (const mount of mounts) {
			const source = isRecord(mount) && typeof mount.source === "string" ? mount.source : "";
			// A `:ro` socket mount restricts the file, not the API: a container holding it can still
			// create containers, and from there mount the host. There is no scoped way to hold this, so
			// no service in this stack holds it — the seed loader reads staging over the network.
			if (source.includes("docker.sock")) violations.push(`${name} mounts the Docker socket`);
		}
		// A preview runs the artifact CI published, not one built here: a build stage would make it a
		// lookalike of the shipped image rather than the shipped image.
		if (service.build !== undefined) {
			violations.push(`${name} builds from pull-request source instead of the published image`);
		}
		// The reference stack pins upstream images through a generated release lock, which Coolify does
		// not supply. A preview therefore pins its own, and a floating tag would silently change what
		// it exercises between two deployments of the same commit.
		const image = typeof service.image === "string" ? service.image : "";
		if (image && !image.startsWith(OWN_IMAGE_PREFIX) && !image.includes("@sha256:")) {
			violations.push(`${name} runs ${image}, an upstream image that is not digest-pinned`);
		}
		if (service.privileged === true) violations.push(`${name} runs privileged`);
		const options = Array.isArray(service.security_opt) ? service.security_opt : [];
		if (!options.includes("no-new-privileges:true")) {
			violations.push(`${name} does not set no-new-privileges`);
		}
		const dropped = Array.isArray(service.cap_drop) ? service.cap_drop : [];
		if (!dropped.includes("ALL")) violations.push(`${name} does not drop all capabilities`);
		const added = Array.isArray(service.cap_add) ? service.cap_add : [];
		const allowed = ALLOWED_CAPABILITIES[name] ?? [];
		for (const capability of added) {
			if (typeof capability !== "string" || !allowed.includes(capability)) {
				violations.push(
					`${name} adds capability ${String(capability)}, which is not recorded here`,
				);
			}
		}
		if (service.network_mode !== undefined) {
			violations.push(`${name} sets network_mode and escapes its own networks`);
		}
		if (Array.isArray(service.ports) && service.ports.length > 0) {
			violations.push(`${name} publishes a port on the shared host`);
		}

		const deploy = isRecord(service.deploy) ? service.deploy : {};
		const resources = isRecord(deploy.resources) ? deploy.resources : {};
		const limits = isRecord(resources.limits) ? resources.limits : {};
		if (limits.memory === undefined || limits.memory === "") {
			violations.push(`${name} has no memory limit and can starve staging`);
		}

		if (name !== "appserver") continue;
		const environment = isRecord(service.environment) ? service.environment : {};
		for (const [key, expected] of Object.entries(REQUIRED_SWITCHES)) {
			const actual = environment[key];
			if (actual !== expected) {
				const shown = typeof actual === "string" ? actual : "«unset»";
				violations.push(`${name} sets ${key}=${shown}, expected ${expected}`);
			}
		}
		for (const key of REQUIRED_NON_EMPTY) {
			const value = environment[key];
			if (typeof value !== "string" || value === "") {
				violations.push(`${name} renders an empty ${key}, which the server refuses to start with`);
			}
		}
	}

	if (!services.some(([name]) => name === "appserver")) {
		violations.push("the rendered stack has no appserver service, so no switch was checked");
	}
	// Coolify runs every preview of this application under one Compose project, named after the
	// application UUID with no pull request in it. A network defined here is therefore `<uuid>_<name>`
	// for all of them at once — a shared network that reads as private. An external one names a
	// network that already exists, so joining it is a decision rather than a side effect.
	for (const [name, network] of records(stack.networks)) {
		if (name === "default") continue;
		if (network.external === true && typeof network.name === "string" && network.name !== "") {
			continue;
		}
		violations.push(`the stack defines the ${name} network, which every preview would share`);
	}

	return violations;
}

export function renderStack(): unknown {
	// The preview stack ships no env file — Coolify supplies every value per pull request — so an
	// inherited COMPOSE_ENV_FILES only ever points Compose at a file this project directory does not
	// have. Drop it so the render means the same thing in CI and on a laptop.
	const { COMPOSE_ENV_FILES: _COMPOSE_ENV_FILES, ...ambient } = process.env;
	const result = spawnSync(
		"docker",
		// `--project-directory` is what Coolify passes, and it is what relative build contexts resolve
		// against. Rendering without it resolves them under docker/preview/ and checks a stack that
		// would never be deployed.
		["compose", "-f", COMPOSE_FILE, "--project-directory", ".", "config", "--format", "json"],
		{ encoding: "utf8", env: { ...ambient, ...RENDER_ENV }, maxBuffer: CAPTURE_LIMIT_BYTES },
	);
	if (result.status !== 0) {
		const unavailable =
			result.status === null ||
			/Cannot connect to the Docker daemon|not found/i.test(result.stderr);
		if (unavailable) return undefined;
		throw new Error(`${COMPOSE_FILE} does not render: ${result.stderr.trim().slice(0, 400)}`);
	}
	try {
		return JSON.parse(result.stdout) as unknown;
	} catch {
		throw new Error(`${COMPOSE_FILE} rendered output that is not JSON.`);
	}
}

if (import.meta.main) {
	try {
		const previewText = readFileSync(COMPOSE_FILE, "utf8");
		const drift = findEnvDrift(readFileSync(REFERENCE_FILE, "utf8"), previewText);
		for (const problem of drift) console.error(`error: ${problem}`);
		const gaps = findSeedPolicyGaps(previewText);
		for (const gap of gaps) console.error(`error: ${COMPOSE_FILE}: ${gap}`);
		if (drift.length + gaps.length > 0) process.exitCode = 1;

		const stack = renderStack();
		if (stack === undefined) {
			console.log(`${COMPOSE_FILE}: skipped, no Docker daemon to render it with.`);
		} else {
			const violations = findViolations(stack);
			for (const violation of violations) console.error(`error: ${COMPOSE_FILE}: ${violation}`);
			if (violations.length > 0) process.exitCode = 1;
			else console.log(`${COMPOSE_FILE}: stack renders and stays sandboxed.`);
		}
	} catch (error) {
		const message = error instanceof Error ? error.message : "unknown preview stack error";
		console.error(`error: ${message.replaceAll(/[\r\n]+/g, " ")}`);
		process.exitCode = 1;
	}
}
