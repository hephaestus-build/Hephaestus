import { spawnSync } from "node:child_process";
import { createHmac, randomUUID } from "node:crypto";
import { appendFileSync, readFileSync, readdirSync, statSync } from "node:fs";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import {
	deliveryDifference,
	partialMigration,
	preservationSql,
	type PreservedPeopleIds,
	type StoredDelivery,
} from "./lib/release-upgrade-fingerprint.ts";
import { parseVersion } from "./lib/release-version.ts";
import { researchConsentFields } from "./lib/research-consent.ts";

const [previousImage = "", candidateImage = "", postgresImage = "", ...options] =
	process.argv.slice(2);

const USAGE =
	"Usage: node scripts/release-upgrade-test.ts <previous-app-image> <candidate-app-image> <postgres-image> [--require-seed=heph-chat]";

if (previousImage === "" || candidateImage === "" || postgresImage === "") {
	throw new Error(USAGE);
}

/**
 * Seeds that a run can require. A seed runs only when the previous release keeps the data it seeds, so a later
 * release that no longer has that data still upgrades without it. A qualification run that must prove one data
 * upgrade requires its seed: the run then fails when the previous release cannot take it.
 */
const REQUIRABLE_SEEDS: ReadonlySet<string> = new Set(["heph-chat"]);
const requiredSeeds = new Set<string>();
for (const option of options) {
	const seed = /^--require-seed=(?<seed>.+)$/u.exec(option)?.groups?.seed;
	if (seed === undefined || !REQUIRABLE_SEEDS.has(seed)) {
		throw new Error(`Unknown option: ${option}\n${USAGE}`);
	}
	requiredSeeds.add(seed);
}
// The upgrade also starts the previous release on the candidate's schema, unless the candidate's migration
// guide entries say that its release stops every role. Those entries come after the previous version.
const previousVersion = process.env.PREVIOUS_VERSION ?? "";
const previousAgentImage = process.env.PREVIOUS_AGENT_IMAGE ?? "";
if (parseVersion(previousVersion) === undefined) {
	throw new Error("The upgrade needs PREVIOUS_VERSION, the previous release as X.Y.Z");
}
if (previousAgentImage === "") {
	throw new Error(
		"The upgrade needs PREVIOUS_AGENT_IMAGE, the agent image of the previous release",
	);
}
const fragmentDirectory = new URL("../.migration/", import.meta.url);
const stopEveryRole = stopEveryRoleEntries(
	readFileSync(new URL("../MIGRATION.md", import.meta.url), "utf8"),
	readdirSync(fragmentDirectory)
		.filter((file) => file.endsWith(".md") && file !== "README.md")
		.map((file) => readFileSync(new URL(file, fragmentDirectory), "utf8")),
	previousVersion,
);

const WORKSPACE_SLUG = "upgrade-fixture";
const ADOPTION_WORKSPACE_SLUG = "upgrade-adoption-fixture";
const PREVIOUS_RELEASE_WORKSPACE_SLUG = "upgrade-previous-fixture";
// A copy of the seeded database that the partial migration runs on.
const PARTIAL_DATABASE = "hephaestus_partial";
const MIGRATION_STOP_MESSAGE = "the upgrade test stops the migration here";
const WEBHOOK_SECRET = "upgrade-test-webhook-secret";
const QUEUED_REVIEW_JOB = "00000000-0000-0000-0000-000000978041";
const HTTP_TIMEOUT_MS = 30_000;
const READINESS_TIMEOUT_MS = 2000;

const runId = `upgrade-${randomUUID().slice(0, 8)}`;
const network = runId;
const postgres = `${runId}-postgres`;
let application = `${runId}-previous`;

function docker(...args: string[]): string {
	const result = spawnSync("docker", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	if (result.status !== 0) {
		throw new Error(`docker ${args.join(" ")} failed:\n${result.stdout}${result.stderr}`);
	}
	return (args[0] === "logs" ? result.stdout + result.stderr : result.stdout).trim();
}

async function fetchWithTimeout(input: string, init?: RequestInit): Promise<Response> {
	return fetch(input, { ...init, signal: AbortSignal.timeout(HTTP_TIMEOUT_MS) });
}

function databaseEnvironment(database: string): string[] {
	return [
		"--env",
		"SPRING_LIQUIBASE_CONTEXTS=prod",
		"--env",
		`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${database}`,
		"--env",
		"SPRING_DATASOURCE_USERNAME=root",
		"--env",
		"SPRING_DATASOURCE_PASSWORD=root",
	];
}

/**
 * The candidate migrates as production does: the migrate run mode first, then a start that only waits for the
 * schema. The previous release may predate the run mode, so it keeps migrating at start.
 */
function migrate(image: string, database = "hephaestus") {
	return spawnSync(
		"docker",
		[
			"run",
			"--rm",
			"--name",
			`${runId}-migration`,
			"--network",
			network,
			"--env",
			"SPRING_PROFILES_ACTIVE=e2e,migrate",
			...databaseEnvironment(database),
			image,
		],
		{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES, timeout: 600_000 },
	);
}

function migrateCandidate(image: string, database = "hephaestus"): void {
	const result = migrate(image, database);
	if (result.status !== 0) {
		throw new Error(
			`The candidate's migration exited with ${String(result.status ?? result.signal)}:\n${result.stdout}${result.stderr}`,
		);
	}
}

/** The previous release's agent image, which its worker pulls and checks at start. */
interface Worker {
	readonly agentImage: string;
}

/** A role set of server and worker in one JVM, which claims queued reviews through the Docker sandbox backend. */
function workerEnvironment(worker: Worker | undefined): string[] {
	if (worker === undefined) {
		// Exercise the server/database upgrade without optional worker infrastructure.
		return ["--env", "HEPHAESTUS_RUNTIME_WORKER_ENABLED=false", "--env", "AGENT_ENABLED=false"];
	}
	const socket = "/var/run/docker.sock";
	return [
		"--volume",
		`${socket}:${socket}`,
		"--group-add",
		String(statSync(socket).gid),
		"--env",
		"HEPHAESTUS_RUNTIME_WORKER_ENABLED=true",
		"--env",
		"AGENT_ENABLED=true",
		"--env",
		`HEPHAESTUS_AGENT_IMAGE_REFERENCE=${worker.agentImage}`,
		// Labels every sandbox resource the worker starts, so the sweep finds them.
		"--env",
		`SANDBOX_DOCKER_OWNER=${runId}`,
		// Readiness reports practice review down without Git checkout.
		"--env",
		"GIT_CHECKOUT_ENABLED=true",
		"--env",
		"HEPHAESTUS_FABRIC_ROOT=/tmp/hephaestus-git",

		// The buildpack's 10M direct-memory default is too small for the Docker client.
		"--env",
		"JAVA_TOOL_OPTIONS=-XX:MaxDirectMemorySize=128M",
	];
}

function startApplication(
	name: string,
	image: string,
	database = "hephaestus",
	migratesAtStart = true,
	worker?: Worker,
): number {
	docker(
		"run",
		"--detach",
		"--name",
		name,
		"--network",
		network,
		"--publish",
		"127.0.0.1::8080",
		"--env",
		"SPRING_PROFILES_ACTIVE=e2e",
		"--env",
		`SPRING_LIQUIBASE_ENABLED=${String(migratesAtStart)}`,
		...databaseEnvironment(database),
		"--env",
		"HEPHAESTUS_SYNC_NATS_ENABLED=false",
		...workerEnvironment(worker),
		"--env",
		"HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED=false",
		"--env",
		"HEPHAESTUS_WORKSPACE_INIT_DEFAULT=false",
		"--env",
		"HEPHAESTUS_SYNC_RUN_ON_STARTUP=false",
		"--env",
		"HEPHAESTUS_SECURITY_ENCRYPTION_KEY=upgradeTestEncryptionKey01234567",
		image,
	);
	return publishedPort(name);
}

function publishedPort(name: string, containerPort = "8080/tcp"): number {
	const mapping = docker("port", name, containerPort);
	const port = Number(mapping.slice(mapping.lastIndexOf(":") + 1));
	if (!Number.isInteger(port)) {
		throw new TypeError(`Could not parse application port from ${mapping}`);
	}
	return port;
}

async function waitUntilReady(
	name: string,
	port: number,
	readinessPaths: readonly string[] = ["/readyz"],
): Promise<void> {
	const deadline = Date.now() + 180_000;
	while (Date.now() < deadline) {
		const state = docker("inspect", "--format", "{{.State.Status}}", name);
		if (state !== "running") {
			throw new Error(`${name} stopped during startup`);
		}
		for (const readinessPath of readinessPaths) {
			try {
				const response = await fetch(`http://127.0.0.1:${port}${readinessPath}`, {
					signal: AbortSignal.timeout(READINESS_TIMEOUT_MS),
				});
				if (response.ok) {
					return;
				}
			} catch {
				// The endpoint is unavailable while the container starts.
			}
		}
		await sleep(2000);
	}
	throw new Error(`${name} did not become ready within 180 seconds`);
}

/**
 * One signed-in caller. A request that changes something sends the CSRF token twice — as a cookie
 * and as the header it is compared against — so the two travel together rather than as a cookie a
 * caller can pair and forget. `SecurityConfig` owns when that is required.
 */
interface Session {
	/** The `Cookie` header: authentication and CSRF cookies together. */
	readonly cookie: string;
	/** Headers a state-changing request must add on top of `cookie`. */
	readonly writeHeaders: Readonly<Record<string, string>>;
}

function cookieNamed(response: Response, suffix: string): string | undefined {
	// The `__Host-` prefix is present or absent with `hephaestus.auth.cookie-secure`, so match the
	// suffix rather than the whole name.
	return response.headers
		.getSetCookie()
		.find((value) => value.slice(0, value.indexOf("=")).endsWith(suffix))
		?.split(";", 1)[0];
}

async function login(port: number, username: string): Promise<Session> {
	const response = await fetchWithTimeout(`http://127.0.0.1:${port}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({
			username,
			displayName: `Upgrade ${username}`,
			admin: username === "root",
		}),
	});
	if (response.status !== 204) {
		throw new Error(`Dev login returned ${response.status}: ${await response.text()}`);
	}
	const authCookie = cookieNamed(response, "HEPHAESTUS_AT");
	if (authCookie === undefined) {
		throw new Error("Dev login did not return an authentication cookie");
	}
	// Fetch a CSRF cookie for the signed-in caller before any write. Dev login is not asked for one,
	// so it returns none to pair with.
	const probe = await fetchWithTimeout(`http://127.0.0.1:${port}/user`, {
		headers: { cookie: authCookie },
	});
	if (!probe.ok) {
		throw new Error(`Reading the signed-in user returned ${probe.status}`);
	}
	// This test drives two releases in turn, and the previous one may predate CSRF enforcement: it
	// issues no token and requires none. Sending the header anyway would be meaningless, and
	// demanding one here would fail the upgrade path rather than test it. A candidate that requires
	// a token still fails loudly — as a 403 on the first request that changes something.
	const csrfCookie = cookieNamed(probe, "XSRF-TOKEN");
	if (csrfCookie === undefined) {
		return { cookie: authCookie, writeHeaders: {} };
	}
	return {
		cookie: `${authCookie}; ${csrfCookie}`,
		writeHeaders: { "x-xsrf-token": csrfCookie.slice(csrfCookie.indexOf("=") + 1) },
	};
}

async function completeTransparencyNotice(port: number, session: Session): Promise<void> {
	const status = await fetchWithTimeout(`http://127.0.0.1:${port}/user/consent`, {
		headers: { cookie: session.cookie },
	});
	if (!status.ok) {
		throw new Error(`Consent status returned ${status.status}: ${await status.text()}`);
	}
	const statusBody: unknown = await status.json();
	if (
		typeof statusBody !== "object" ||
		statusBody === null ||
		!("noticeVersion" in statusBody) ||
		typeof statusBody.noticeVersion !== "string"
	) {
		throw new Error("Consent status did not return the current notice version");
	}
	if ("completed" in statusBody && statusBody.completed === true) {
		return;
	}
	const completed = await fetchWithTimeout(`http://127.0.0.1:${port}/user/consent`, {
		method: "PUT",
		headers: {
			"content-type": "application/json",
			cookie: session.cookie,
			...session.writeHeaders,
		},
		body: JSON.stringify({
			noticeVersion: statusBody.noticeVersion,
			termsAccepted: true,
			...researchConsentFields(
				"researchOrganization" in statusBody ? statusBody.researchOrganization : undefined,
			),
		}),
	});
	if (!completed.ok) {
		throw new Error(
			`Completing the transparency notice returned ${completed.status}: ${await completed.text()}`,
		);
	}
}

async function assertCoreReads(port: number, session: Session): Promise<void> {
	const user = await fetchWithTimeout(`http://127.0.0.1:${port}/user`, {
		headers: { cookie: session.cookie },
	});
	if (!user.ok) {
		throw new Error(`Core read /user returned ${user.status}: ${await user.text()}`);
	}
	const userBody: unknown = await user.json();
	if (
		typeof userBody !== "object" ||
		userBody === null ||
		!("displayName" in userBody) ||
		userBody.displayName !== "Upgrade alice"
	) {
		throw new Error("Core read /user did not return the seeded account");
	}

	const providers = await fetchWithTimeout(`http://127.0.0.1:${port}/identity-providers`);
	if (!providers.ok) {
		throw new Error(
			`Core read /identity-providers returned ${providers.status}: ${await providers.text()}`,
		);
	}
	const providerBody: unknown = await providers.json();
	if (!Array.isArray(providerBody) || providerBody.length === 0) {
		throw new Error("Core read /identity-providers returned no providers");
	}
	const workspaces = await fetchWithTimeout(`http://127.0.0.1:${port}/workspaces`, {
		headers: { cookie: session.cookie },
	});
	if (!workspaces.ok) {
		throw new Error(
			`Core read /workspaces returned ${workspaces.status}: ${await workspaces.text()}`,
		);
	}
	const body: unknown = await workspaces.json();
	if (
		!Array.isArray(body) ||
		!body.some(
			(workspace: unknown) =>
				typeof workspace === "object" &&
				workspace !== null &&
				"workspaceSlug" in workspace &&
				workspace.workspaceSlug === WORKSPACE_SLUG,
		)
	) {
		throw new Error("Core read /workspaces did not return the seeded workspace");
	}
}

async function seedWorkspace(port: number, session: Session, workspaceSlug: string): Promise<void> {
	const response = await fetchWithTimeout(`http://127.0.0.1:${port}/workspaces`, {
		method: "POST",
		headers: {
			"content-type": "application/json",
			cookie: session.cookie,
			...session.writeHeaders,
		},
		body: JSON.stringify({
			workspaceSlug,
			displayName: "Upgrade Fixture",
			accountLogin: workspaceSlug,
			accountType: "ORG",
			kind: "GITHUB",
			personalAccessToken: "upgrade-fixture-token",
		}),
	});
	if (response.status !== 201) {
		throw new Error(`Workspace seed returned ${response.status}: ${await response.text()}`);
	}
}

async function assertInstalledCatalog(port: number, session: Session): Promise<void> {
	const root = await login(port, "root");
	await completeTransparencyNotice(port, root);
	const response = await fetchWithTimeout(`http://127.0.0.1:${port}/admin/practice-catalog`, {
		headers: { cookie: root.cookie },
	});
	if (!response.ok) {
		throw new Error(`Instance catalog returned ${response.status}`);
	}
	const catalog = asRecord(await response.json(), "Instance catalog");
	const offered = asArray(catalog.practices, "Instance catalog.practices")
		.map((entry) => asRecord(entry, "Catalog practice"))
		.filter((practice) => practice.offered === true)
		.map((practice) => asString(practice.slug, "Catalog practice.slug"))
		.toSorted();
	if (offered.length === 0) {
		throw new Error("Instance catalog has no offered practices");
	}
	const installedResponse = await fetchWithTimeout(
		`http://127.0.0.1:${port}/workspaces/${ADOPTION_WORKSPACE_SLUG}/practices`,
		{ headers: { cookie: session.cookie } },
	);
	if (!installedResponse.ok) {
		throw new Error(`Workspace practices returned ${installedResponse.status}`);
	}
	const installed = asArray(await installedResponse.json(), "Workspace practices")
		.map((entry) => asString(asRecord(entry, "Workspace practice").slug, "Workspace practice.slug"))
		.toSorted();
	if (
		workspacePracticeCount(ADOPTION_WORKSPACE_SLUG) !== offered.length ||
		JSON.stringify(installed) !== JSON.stringify(offered)
	) {
		throw new Error("New workspace practices do not match the offered instance catalog");
	}
	if (
		count(`SELECT count(*) FROM workspace w
		WHERE coalesce(to_jsonb(w)->>'workspace_slug', to_jsonb(w)->>'slug') = '${ADOPTION_WORKSPACE_SLUG}'
		AND w.practice_catalog_installed_at IS NOT NULL`) !== 1
	) {
		throw new Error("New workspace has no completed catalog installation");
	}
	if (
		count(`SELECT count(*) FROM practice p JOIN workspace w ON w.id = p.workspace_id
		LEFT JOIN practice_version v ON v.id = p.current_version_id
		WHERE coalesce(to_jsonb(w)->>'workspace_slug', to_jsonb(w)->>'slug') = '${ADOPTION_WORKSPACE_SLUG}'
		AND (v.id IS NULL OR v.practice_id <> p.id OR v.workspace_id <> p.workspace_id
			OR v.number <> 1 OR (SELECT count(*) FROM practice_version x WHERE x.practice_id = p.id) <> 1)`) !==
		0
	) {
		throw new Error("New workspace practices do not have one valid initial version each");
	}
}

function linkWorkspaceIdentity(): void {
	// Native entity initializers and @CreationTimestamp are not database defaults, so every
	// non-null identity column is written.
	const link = `INSERT INTO identity
        (account_id, provider_id, provider_user_key, team_key, linked_at, linked_via)
        SELECT fixture.account_id, created.provider_id, created.native_id::text, '', now(), 'MANUAL'`;
	const linked = query(`WITH fixture AS (
		   SELECT account.id AS account_id, identity_provider.id AS provider_id
		   FROM account CROSS JOIN identity_provider
		   WHERE account.primary_email = 'alice@dev.invalid'
		   ORDER BY identity_provider.id LIMIT 1
		 ), created AS (
		   INSERT INTO "user" (provider_id, native_id, login, avatar_url, html_url, type)
		   SELECT provider_id, 424242, 'account:' || account_id, 'https://example.invalid/avatar',
		          'https://example.invalid/user', 'USER'
		   FROM fixture
		   RETURNING id, provider_id, native_id
		 )
		 ${link}
		 FROM fixture CROSS JOIN created
		 RETURNING id;`);
	// A fixture that matches nothing inserts nothing without an error.
	if (!/^[1-9][0-9]*$/u.test(linked)) {
		throw new Error(`Fixture identity link must insert exactly one row, received: ${linked}`);
	}
}

function query(sql: string, database = "hephaestus"): string {
	return docker(
		"exec",
		postgres,
		"psql",
		"--username=root",
		`--dbname=${database}`,
		"--set=ON_ERROR_STOP=1",
		// Without it a write's command tag follows the rows it returns.
		"--quiet",
		"--tuples-only",
		"--no-align",
		"--command",
		sql,
	);
}

function dataFingerprint(preserved: PreservedPeopleIds): string {
	return query(preservationSql(preserved));
}

const rows = (table: string) => {
	const value = query(`SELECT id FROM ${table} ORDER BY id`);
	return value === "" ? [] : value.split("\n");
};

function preservedPeople(): PreservedPeopleIds {
	return {
		accounts: rows("account"),
		identities: rows("identity"),
	};
}

function executeFixture(
	name: "released-seed.sql" | "assert-migrated.sql" | "assert-upgraded.sql" | "result-parent.sql",
	directory: "practice-cutover" | "heph-upgrade" | "practice-native" = "practice-cutover",
	database = "hephaestus",
): void {
	const input = readFileSync(
		new URL(`../server/application/src/test/resources/db/${directory}/${name}`, import.meta.url),
		"utf8",
	);
	const result = spawnSync(
		"docker",
		[
			"exec",
			"--interactive",
			postgres,
			"psql",
			"--username=root",
			`--dbname=${database}`,
			"--set=ON_ERROR_STOP=1",
			"--single-transaction",
			"--file=-",
		],
		{ input, encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
	);
	if (result.status !== 0) {
		throw new Error(`Upgrade fixture ${name} failed: ${result.stderr}`);
	}
}

function count(sql: string, database = "hephaestus"): number {
	const value = docker(
		"exec",
		postgres,
		"psql",
		"--username=root",
		`--dbname=${database}`,
		"--tuples-only",
		"--no-align",
		"--command",
		sql,
	);
	if (!/^[0-9]+$/u.test(value)) {
		throw new Error(`Expected an integer query result, received: ${value}`);
	}
	return Number(value);
}

function workspacePracticeCount(workspaceSlug: string): number {
	return count(`
		SELECT count(*)
		FROM practice
		JOIN workspace ON workspace.id = practice.workspace_id
		WHERE coalesce(
			to_jsonb(workspace)->>'workspace_slug',
			to_jsonb(workspace)->>'slug'
		) = '${workspaceSlug}';
	`);
}

/** Whether the previous release keeps Heph chat history that conversation copies point to. */
function keepsHephChatHistory(): boolean {
	return (
		count(`SELECT count(*) FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = 'feedback_copy' AND column_name = 'chat_message_id';`) ===
		1
	);
}

/**
 * Every column, constraint, index, trigger and function of the application schema, one sorted line each. Liquibase's
 * own tables and runtime partitions are left out: they are not part of what a changelog decides.
 */
function schemaFingerprint(database: string): string[] {
	const facts = query(
		`SELECT line FROM (
			SELECT 'column|' || table_name || '|' || column_name || '|' || data_type || '|'
				|| coalesce(character_maximum_length::text, '') || '|' || is_nullable || '|' || coalesce(column_default, '')
				AS line
			FROM information_schema.columns WHERE table_schema = 'public'
			UNION ALL
			SELECT 'constraint|' || conrelid::regclass || '|' || conname || '|' || pg_get_constraintdef(oid)
			FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conrelid <> 0
			UNION ALL
			SELECT 'index|' || tablename || '|' || indexname || '|' || indexdef FROM pg_indexes WHERE schemaname = 'public'
			UNION ALL
			SELECT 'trigger|' || tgrelid::regclass || '|' || tgname || '|' || pg_get_triggerdef(oid)
			FROM pg_trigger WHERE NOT tgisinternal
			UNION ALL
			SELECT 'function|' || proname || '|' || md5(prosrc) FROM pg_proc WHERE pronamespace = 'public'::regnamespace
		) facts
		WHERE line NOT LIKE '%databasechangelog%' AND line !~ '\\|auth_event_(p[0-9]+|default)\\|'
		ORDER BY line;`,
		database,
	);
	return facts === "" ? [] : facts.split("\n");
}

/** Starts the candidate once on an empty database and compares its schema with the upgraded one. */
async function assertFreshSchemaEqualsUpgraded(image: string): Promise<void> {
	docker("exec", postgres, "createdb", "--username=root", "hephaestus_fresh");
	const fresh = `${runId}-fresh`;
	try {
		migrateCandidate(image, "hephaestus_fresh");
		await waitUntilReady(fresh, startApplication(fresh, image, "hephaestus_fresh", false));
	} finally {
		spawnSync("docker", ["rm", "--force", fresh], { stdio: "ignore" });
	}
	const upgraded = schemaFingerprint("hephaestus");
	const created = schemaFingerprint("hephaestus_fresh");
	const onlyUpgraded = upgraded.filter((line) => !created.includes(line));
	const onlyFresh = created.filter((line) => !upgraded.includes(line));
	if (onlyUpgraded.length > 0 || onlyFresh.length > 0) {
		throw new Error(
			`The upgraded schema differs from a fresh one:\n- only upgraded:\n${onlyUpgraded.join("\n")}\n- only fresh:\n${onlyFresh.join("\n")}`,
		);
	}
	console.log(`Fresh and upgraded schemas match (${upgraded.length} facts).`);
}

function appliedChangeCount(database = "hephaestus"): number {
	return count("SELECT count(*) FROM databasechangelog;", database);
}

/** A check that passed without testing anything: a warning in the log and a line in the job summary. */
function summarize(line: string): void {
	console.log(`::warning::${line}`);
	const summary = process.env.GITHUB_STEP_SUMMARY;
	if (summary !== undefined && summary !== "") {
		appendFileSync(summary, `- ${line}\n`);
	}
}

/** Removes the sandbox containers, networks and volumes that the previous release's worker started. */
function sweepSandboxes(): void {
	const filter = `label=hephaestus.sandbox-owner=${runId}`;
	for (const kind of ["container", "network", "volume"] as const) {
		const listed = spawnSync(
			"docker",
			kind === "container"
				? ["ps", "--all", "--quiet", "--filter", filter]
				: [kind, "ls", "--quiet", "--filter", filter],
			{ encoding: "utf8" },
		);
		// A docker that cannot start leaves nothing to sweep, and must not hide the error that ended the run.
		if (listed.error !== undefined) {
			continue;
		}
		const ids = listed.stdout.split("\n").filter(Boolean);
		if (ids.length > 0) {
			spawnSync("docker", kind === "container" ? ["rm", "--force", ...ids] : [kind, "rm", ...ids], {
				stdio: "ignore",
			});
		}
	}
}

function printLogs(name: string): void {
	try {
		console.error(`\n--- ${name} logs ---\n${docker("logs", name)}`);
	} catch {
		// Preserve the upgrade failure if diagnostic collection also fails.
	}
}

/**
 * Starts the previous release on a schema that is ahead of it, as a rollout or a rollback does, and smokes it:
 * readiness, the core reads, and a new workspace, which writes the workspace and its practice catalog. The e2e
 * profile validates every entity against the schema at start. Its own migration finds nothing to apply.
 */
async function assertPreviousReleaseRuns(database: string, schema: string): Promise<void> {
	const name = `${runId}-previous-on-${database}`;
	try {
		withFixture(database, () => seedReview(database));
		const port = startApplication(name, previousImage, database, true, {
			agentImage: previousAgentImage,
		});
		await waitUntilReady(name, port, ["/readyz", "/actuator/health/readiness"]);
		const session = await login(port, "alice");
		await completeTransparencyNotice(port, session);
		await assertCoreReads(port, session);
		await seedWorkspace(port, session, PREVIOUS_RELEASE_WORKSPACE_SLUG);
		const modelId = await bindReviewModel(port, session);
		withFixture(database, () => queueReview(database, modelId));
		console.log(
			`The previous release claimed the queued review on ${schema}: ${await claimedReview(database)}.`,
		);
	} catch (error) {
		printLogs(name);
		throw error;
	} finally {
		spawnSync("docker", ["rm", "--force", name], { stdio: "ignore" });
		sweepSandboxes();
	}
	console.log(`The previous release runs on ${schema}.`);
}

const REVIEW_ID = "00000000-0000-0000-0000-000000978011";
const REVIEW_WORKSPACE = "native-results";

/** Runs the candidate's own fixture SQL, which fits the candidate's full schema, on `database`. */
function withFixture(database: string, seed: () => void): void {
	try {
		seed();
	} catch (error) {
		throw new Error(
			`The candidate's review fixture does not fit the schema of ${database}. Fix the fixture, not the release: ${error instanceof Error ? error.message : String(error)}`,
			{ cause: error },
		);
	}
}

/**
 * Seeds a review of a pull request with one practice, from the candidate's own fixture for that graph
 * (`practice-native/result-parent.sql`). Alice administers its workspace, so she can bind the review model.
 */
function seedReview(database: string): void {
	executeFixture("result-parent.sql", "practice-native", database);
	query(
		`UPDATE review_item SET detail = '{"developerId":978003}' WHERE review_id = '${REVIEW_ID}';
		INSERT INTO membership (workspace_id, account_id, role)
		SELECT 978001, id, 'ADMIN' FROM account WHERE primary_email = 'alice@dev.invalid'`,
		database,
	);
}

async function write(
	port: number,
	session: Session,
	method: "POST" | "PUT" | "PATCH",
	path: string,
	body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
	const response = await fetchWithTimeout(`http://127.0.0.1:${port}${path}`, {
		method,
		headers: {
			"content-type": "application/json",
			cookie: session.cookie,
			...session.writeHeaders,
		},
		body: JSON.stringify(body),
	});
	const text = await response.text();
	if (!response.ok) {
		throw new Error(`${method} ${path} returned ${response.status}: ${text}`);
	}
	return text === "" ? {} : asRecord(parseJson(text), `${method} ${path}`);
}

/**
 * Creates an AI model on a connection that nothing answers, and binds it to practice reviews in the review's
 * workspace, through the previous release's own API. Returns the model id.
 */
async function bindReviewModel(port: number, alice: Session): Promise<number> {
	const root = await login(port, "root");
	await completeTransparencyNotice(port, root);
	const connection = await write(port, root, "POST", "/admin/llm/connections", {
		displayName: "Upgrade test",
		baseUrl: "http://127.0.0.1:9/v1",
		apiProtocol: "openai-completions",
		apiKey: "upgrade-test-key",
		enabled: true,
	});
	const model = await write(
		port,
		root,
		"POST",
		`/admin/llm/connections/${String(connection.id)}/models`,
		{ displayName: "Upgrade model", upstreamModelId: "upgrade-model" },
	);
	const modelId = Number(model.id);
	await write(port, root, "PUT", `/admin/llm/models/${modelId}/price`, {
		pricingMode: "NO_CHARGE",
		note: "Self-hosted test model",
	});
	await write(port, root, "PATCH", `/admin/llm/models/${modelId}`, { enabled: true });
	await write(port, root, "PUT", `/admin/llm/models/${modelId}/sharing`, { visibility: "PUBLIC" });
	await write(port, alice, "PUT", `/workspaces/${REVIEW_WORKSPACE}/agents/PRACTICE_REVIEW`, {
		instanceModelId: modelId,
		maxConcurrentJobs: 1,
		enabled: true,
	});
	return modelId;
}

/**
 * Queues the review as the candidate's enqueue path (`ReviewJobService.enqueue`) writes it. The practice pins the
 * review method with every model slot (`ReviewModelBindingsAdapter`): `review` and `chat` hold the bound model, the
 * other slots hold none. The job freezes that model with its NO_CHARGE price, and also as the `CHAT` practice-script
 * model. The review item points at the job. The previous release's worker must admit all of it.
 */
function queueReview(database: string, modelId: number): void {
	const frozen = `jsonb_build_object('modelId', 'instance:' || m.id, 'providerProtocol', 'openai-completions',
		'providerModelId', 'upgrade-model', 'reasoningEffort', null, 'options', jsonb_build_object(
			'practiceModelCalls', 12, 'practiceOutputTokens', 16000,
			'directModelTokensPerAttempt', 200000, 'directModelSearchUnitsPerAttempt', 1000))`;
	const runtime = `jsonb_build_object('apiProtocol', 'openai-completions', 'baseUrl', 'http://127.0.0.1:9/v1',
		'upstreamModelId', 'upgrade-model', 'connectionScope', 'INSTANCE', 'connectionId', m.connection_id,
		'modelId', m.id, 'workspaceId', b.workspace_id, 'dataHandlingTier', b.data_handling_tier,
		'reasoningEffort', null, 'priceSnapshot', jsonb_build_object('fundingSource', 'INSTANCE',
			'pricingState', 'NO_CHARGE', 'appliedPriceId', p.id, 'appliedWorkspaceModelId', null,
			'per1mInputUsd', p.per_1m_input_usd, 'per1mOutputUsd', p.per_1m_output_usd,
			'per1mCacheReadUsd', p.per_1m_cache_read_usd, 'per1mCacheWriteUsd', p.per_1m_cache_write_usd))`;
	const bound = `FROM llm_model m
		JOIN llm_model_price p ON p.model_id = m.id AND p.effective_to IS NULL
		JOIN workspace_agent_binding b ON b.instance_model_id = m.id AND b.workspace_id = 978001
			AND b.purpose = 'PRACTICE_REVIEW'
		WHERE m.id = ${modelId}`;
	query(
		`WITH method AS (SELECT jsonb_build_array(
				jsonb_build_object('slot', 'chat', 'kind', 'CHAT', 'model', ${frozen}),
				jsonb_build_object('slot', 'decision', 'kind', 'DECISION', 'model', null),
				jsonb_build_object('slot', 'embedding', 'kind', 'EMBEDDING', 'model', null),
				jsonb_build_object('slot', 'reranking', 'kind', 'RERANKING', 'model', null),
				jsonb_build_object('slot', 'review', 'kind', 'CHAT', 'model', ${frozen})) AS models
			${bound})
		UPDATE review_practice SET method = jsonb_build_object('epoch', 1, 'models', method.models,
			'modelDigest', review_model_digest(method.models))
		FROM method WHERE review_id = '${REVIEW_ID}';
		WITH snapshot AS (SELECT ${runtime} AS model, b.timeout_seconds ${bound})
		INSERT INTO review_job (id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,config_snapshot)
		SELECT '${QUEUED_REVIEW_JOB}','${REVIEW_ID}',978001,now(),'QUEUED','NONE','00000000000000000000000000000001',
			'{"itemId":978006,"assignments":[{"reviewId":"${REVIEW_ID}","practiceId":978004,"itemId":978006}]}',
			jsonb_build_object('schemaVersion', 8, 'model', model, 'contextWindow', null, 'maxOutputTokens', null,
				'timeoutSeconds', timeout_seconds, 'allowInternet', false,
				'practiceScript', jsonb_build_object('CHAT', model))
		FROM snapshot;
		UPDATE review_item SET job_id = '${QUEUED_REVIEW_JOB}' WHERE review_id = '${REVIEW_ID}'`,
		database,
	);
	if (count(`SELECT count(*) FROM review_job WHERE id = '${QUEUED_REVIEW_JOB}'`, database) !== 1) {
		throw new Error(
			"The queued review job was not written: the model, its price or its binding is missing",
		);
	}
}

/**
 * Waits until the previous release's worker claims the queued review through its native claim queries: the job is
 * started under this worker. The attempt fails afterwards, as it cannot capture the fixture's pull request.
 */
async function claimedReview(database: string): Promise<string> {
	const deadline = Date.now() + 120_000;
	let job = "";
	while (Date.now() < deadline) {
		const row = query(
			`SELECT attempt || '|' || (worker_id IS NOT NULL AND started_at IS NOT NULL) || '|' || status || '|'
				|| concat_ws(' ', 'status=' || status, 'attempt=' || attempt, 'worker=' || worker_id,
					'started=' || started_at, 'completed=' || completed_at, 'reason=' || cancellation_reason,
					'error=' || left(error_message, 120))
			FROM review_job WHERE id='${QUEUED_REVIEW_JOB}'`,
			database,
		);
		const [attempt = "", running = "", status = "", ...summary] = row.split("|");
		job = summary.join("|");
		// A failed attempt can be requeued as an orphan between two reads: it then has no worker, but a higher attempt.
		if (running === "true" || Number(attempt) > 0) {
			return job;
		}
		if (row !== "" && status !== "QUEUED" && status !== "RUNNING") {
			throw new Error(`The previous release ended the queued review without running it: ${job}`);
		}
		await sleep(2000);
	}
	throw new Error(
		`The previous release did not claim the queued review within 120 seconds: ${job}`,
	);
}

/**
 * Stops the candidate's migration after about half of its new change sets, as a failed change set stops it: the failed
 * change set rolls back. The stop is an event trigger on the next change set's first DDL statement, so a change set
 * without DDL can pass it. A trigger on the history insert would not do: Liquibase 5.0.x commits a change set before
 * its history row. The previous release must run on that prefix, and the migration must complete when it runs again.
 * `database` is a copy of the seeded database from before the upgrade.
 */
async function assertPartialMigration(
	database: string,
	before: number,
	pending: number,
): Promise<void> {
	if (pending < 2) {
		summarize(
			`The candidate adds ${pending} change sets to the previous release, so no partial schema lies between them.`,
		);
		return;
	}
	const applied = Math.floor(pending / 2);
	query(
		`CREATE FUNCTION stop_upgrade_test_migration() RETURNS event_trigger LANGUAGE plpgsql AS $$
		BEGIN
			IF (SELECT count(*) FROM databasechangelog) >= ${before + applied} THEN
				RAISE EXCEPTION '${MIGRATION_STOP_MESSAGE}';
			END IF;
		END $$;
		CREATE EVENT TRIGGER stop_upgrade_test_migration ON ddl_command_start
			EXECUTE FUNCTION stop_upgrade_test_migration();`,
		database,
	);
	let stopped: ReturnType<typeof migrate>;
	try {
		stopped = migrate(candidateImage, database);
	} finally {
		query(
			"DROP EVENT TRIGGER stop_upgrade_test_migration; DROP FUNCTION stop_upgrade_test_migration();",
			database,
		);
	}
	const output = `${stopped.stdout}${stopped.stderr}`;
	const outcome = partialMigration(
		stopped.status,
		output.includes(MIGRATION_STOP_MESSAGE),
		appliedChangeCount(database) - before,
		applied,
		pending,
	);
	if (outcome.kind === "failed") {
		throw new Error(`The candidate's stopped migration is wrong: ${outcome.reason}:\n${output}`);
	}
	if (outcome.kind === "complete") {
		summarize(
			`No change set after ${applied} runs DDL, so no partial prefix exists. The migration completed.`,
		);
		return;
	}
	console.log(
		`The candidate's migration stopped after ${outcome.reached} of ${pending} new change sets.`,
	);
	if (stopEveryRole.length === 0) {
		await assertPreviousReleaseRuns(database, `the partly migrated schema`);
	}
	migrateCandidate(candidateImage, database);
	const completed = appliedChangeCount(database) - before;
	if (completed !== pending) {
		throw new Error(`The repeated migration applied ${completed} of ${pending} new change sets`);
	}
	console.log("The candidate's migration completed when it ran again.");
}

function startWebhookReceiver(name: string, image: string, database: string): number {
	docker(
		"run",
		"--detach",
		"--name",
		name,
		"--network",
		network,
		"--publish",
		"127.0.0.1::8080",
		"--env",
		"SPRING_PROFILES_ACTIVE=e2e,webhook",
		...databaseEnvironment(database),
		"--env",
		"HEPHAESTUS_SYNC_NATS_SERVER=nats://nats:4222",
		"--env",
		`WEBHOOK_SECRET=${WEBHOOK_SECRET}`,
		// The defaults reserve more broker storage than a runner has. A stream must hold four maximal payloads.
		"--env",
		"HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES=128MB",
		"--env",
		"HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB=128MB",
		"--env",
		"HEPHAESTUS_WEBHOOK_STREAM_STORAGE_BUDGET=512MB",
		"--env",
		"HEPHAESTUS_SECURITY_ENCRYPTION_KEY=upgradeTestEncryptionKey01234567",
		image,
	);
	return publishedPort(name);
}

/**
 * One delivery for each provider on the shared receiver path. GitLab and Outline deliver only on a route credential
 * that the server issues for a stored connection.
 */
const WEBHOOK_DELIVERIES = [
	{
		stream: "github",
		body: JSON.stringify({
			action: "opened",
			repository: { name: "upgrade-repository", owner: { login: WORKSPACE_SLUG } },
		}),
		headers: (delivery: string, body: string): Record<string, string> => ({
			"x-github-event": "issues",
			"x-github-delivery": delivery,
			"x-hub-signature-256": `sha256=${createHmac("sha256", WEBHOOK_SECRET).update(body).digest("hex")}`,
		}),
	},
] as const;

/**
 * The webhook receivers of both releases publish the same deliveries. Apart from the delivery id, each pair of stream
 * messages must have the same subject and body, and the candidate must keep every header of the previous release. It
 * may add one. Each release tests its own receiver against its own consumer, so the server of either release reads
 * the messages of the other receiver, unless the candidate's server needs a header it added. In a rolling upgrade the
 * previous receiver keeps running on the candidate's schema, so it starts there. A release that stops every role
 * never runs it on that schema, so it starts on the pre-upgrade database copy instead.
 */
async function assertWebhookContract(): Promise<void> {
	const nats = `${runId}-nats`;
	const receivers = [`${runId}-webhook-previous`, `${runId}-webhook-candidate`] as const;
	const release = asArray(
		asRecord(
			parseJson(readFileSync(new URL("../security/release-images.json", import.meta.url), "utf8")),
			"release-images.json",
		).upstream,
		"release-images.json upstream",
	)
		.map((entry) => asRecord(entry, "release image"))
		.find((entry) => entry.name === "nats");
	if (release === undefined) {
		throw new Error("security/release-images.json pins no nats image");
	}
	const [{ connect }, { jetstreamManager }] = await Promise.all([
		import("@nats-io/transport-node"),
		import("@nats-io/jetstream"),
	]);
	const natsImage = `${asString(release.repository, "nats repository")}:${asString(release.tag, "nats tag")}@${asString(release.digest, "nats digest")}`;
	try {
		docker(
			"run",
			"--detach",
			"--name",
			nats,
			"--network",
			network,
			"--network-alias",
			"nats",
			"--publish",
			"127.0.0.1::4222",
			natsImage,
			"nats-server",
			"--jetstream",
		);
		// In stream order: the previous receiver publishes first.
		const targets = [
			{
				name: receivers[0],
				port: startWebhookReceiver(
					receivers[0],
					previousImage,
					stopEveryRole.length === 0 ? "hephaestus" : PARTIAL_DATABASE,
				),
			},
			{
				name: receivers[1],
				port: startWebhookReceiver(receivers[1], candidateImage, "hephaestus"),
			},
		].map((target) => ({ ...target, delivery: randomUUID() }));
		await Promise.all(
			targets.map(async ({ name, port }) =>
				waitUntilReady(name, port, ["/readyz", "/actuator/health/readiness"]),
			),
		);
		for (const delivery of WEBHOOK_DELIVERIES) {
			for (const target of targets) {
				const response = await fetchWithTimeout(
					`http://127.0.0.1:${target.port}/webhooks/${delivery.stream}`,
					{
						method: "POST",
						headers: {
							"content-type": "application/json",
							...delivery.headers(target.delivery, delivery.body),
						},
						body: delivery.body,
					},
				);
				const answer = await response.text();
				if (response.status !== 202 || !answer.includes('"ok"')) {
					throw new Error(
						`${target.name} answered a ${delivery.stream} delivery with ${response.status}: ${answer}`,
					);
				}
			}
		}
		const port = publishedPort(nats, "4222/tcp");
		const connection = await connect({ servers: `127.0.0.1:${port}` });
		try {
			const manager = await jetstreamManager(connection);
			for (const delivery of WEBHOOK_DELIVERIES) {
				const [previous, candidate] = await Promise.all(
					targets.map(async ({ delivery: id }, index): Promise<StoredDelivery> => {
						const message = await manager.streams.getMessage(delivery.stream, {
							seq: index + 1,
						});
						if (message === null) {
							throw new Error(`The ${delivery.stream} stream has no message ${index + 1}`);
						}
						return {
							subject: message.subject,
							headers: message.header
								.keys()
								.toSorted()
								.map((key) => `${key}: ${message.header.get(key).replaceAll(id, "<delivery>")}`),
							body: message.string(),
						};
					}),
				);
				if (previous === undefined || candidate === undefined) {
					throw new Error(`The ${delivery.stream} stream lacks a delivery`);
				}
				const difference = deliveryDifference(previous, candidate);
				if (difference !== undefined) {
					throw new Error(
						`The server of the previous release cannot read the candidate's ${delivery.stream} delivery: ${difference}.\n- previous: ${JSON.stringify(previous)}\n- candidate: ${JSON.stringify(candidate)}`,
					);
				}
				console.log(
					`Webhook NATS protocol passed (${previousVersion} receiver -> candidate, ${delivery.stream}): ${JSON.stringify(candidate)}.`,
				);
			}
		} finally {
			await connection.close();
		}
	} catch (error) {
		for (const name of [...receivers, nats]) {
			printLogs(name);
		}
		throw error;
	} finally {
		spawnSync("docker", ["rm", "--force", ...receivers, nats], { stdio: "ignore" });
	}
}

try {
	docker("network", "create", network);
	docker(
		"run",
		"--detach",
		"--name",
		postgres,
		"--network",
		network,
		"--network-alias",
		"postgres",
		"--env",
		"POSTGRES_DB=hephaestus",
		"--env",
		"POSTGRES_USER=root",
		"--env",
		"POSTGRES_PASSWORD=root",
		postgresImage,
	);

	for (let attempt = 0; attempt < 60; attempt += 1) {
		try {
			docker("exec", postgres, "pg_isready", "--username=root", "--dbname=hephaestus");
			break;
		} catch {
			if (attempt === 59) {
				throw new Error("PostgreSQL did not become ready");
			}
			await sleep(1000);
		}
	}

	let port = startApplication(application, previousImage);
	// A previous release without the management port answers readiness only on the actuator path.
	await waitUntilReady(application, port, ["/readyz", "/actuator/health/readiness"]);
	const previousSession = await login(port, "alice");
	await completeTransparencyNotice(port, previousSession);
	await login(port, "root");
	linkWorkspaceIdentity();
	await seedWorkspace(port, previousSession, WORKSPACE_SLUG);
	await assertCoreReads(port, previousSession);
	docker("stop", "--time", "30", application);
	docker("rm", application);
	const hephChatSource = keepsHephChatHistory();
	if (hephChatSource) {
		executeFixture("released-seed.sql", "heph-upgrade");
	} else if (requiredSeeds.has("heph-chat")) {
		throw new Error(
			"The required heph-chat seed was not admitted: the previous release keeps no Heph chat history",
		);
	}
	const preserved = preservedPeople();
	const seededData = dataFingerprint(preserved);
	for (const kind of ["account", "identity", "user", "workspace", "membership", "connection"]) {
		if (!seededData.includes(`${kind}|`)) {
			throw new Error(`Previous release did not seed ${kind} data`);
		}
	}
	const previousPractices = workspacePracticeCount(WORKSPACE_SLUG);
	const previousChanges = appliedChangeCount();

	// The template must have no other session.
	query(
		"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'hephaestus' AND pid <> pg_backend_pid()",
		"postgres",
	);
	docker(
		"exec",
		postgres,
		"createdb",
		"--username=root",
		"--template=hephaestus",
		PARTIAL_DATABASE,
	);
	migrateCandidate(candidateImage);
	application = `${runId}-candidate`;
	port = startApplication(application, candidateImage, "hephaestus", false);
	await waitUntilReady(application, port);
	const upgradedData = dataFingerprint(preserved);
	executeFixture("assert-migrated.sql");
	if (hephChatSource) {
		executeFixture("assert-upgraded.sql", "heph-upgrade");
	}
	if (upgradedData !== seededData) {
		throw new Error("Seeded application data changed during upgrade");
	}
	const upgradedPractices = workspacePracticeCount(WORKSPACE_SLUG);
	if (upgradedPractices < previousPractices) {
		throw new Error(
			`Workspace practices shrank during upgrade: before=${previousPractices}, after=${upgradedPractices}`,
		);
	}
	const candidateChanges = appliedChangeCount();
	if (candidateChanges < previousChanges) {
		throw new Error(
			`Liquibase history shrank during upgrade: before=${previousChanges}, after=${candidateChanges}`,
		);
	}
	const candidateSession = await login(port, "alice");
	await completeTransparencyNotice(port, candidateSession);
	await assertCoreReads(port, candidateSession);

	await seedWorkspace(port, candidateSession, ADOPTION_WORKSPACE_SLUG);
	await assertInstalledCatalog(port, candidateSession);
	const installedPractices = workspacePracticeCount(ADOPTION_WORKSPACE_SLUG);
	docker("restart", "--time", "30", application);
	port = publishedPort(application);
	await waitUntilReady(application, port);
	const restartedSession = await login(port, "alice");
	await assertInstalledCatalog(port, restartedSession);
	if (
		workspacePracticeCount(WORKSPACE_SLUG) !== upgradedPractices ||
		workspacePracticeCount(ADOPTION_WORKSPACE_SLUG) !== installedPractices
	) {
		throw new Error("Workspace practice counts changed after restart");
	}

	if (stopEveryRole.length === 0) {
		docker("pull", "--quiet", previousAgentImage);
		await assertPreviousReleaseRuns("hephaestus", "the candidate's schema");
	} else {
		summarize(
			`Previous application/schema boot coverage is off for this release, because it stops every role: ${stopEveryRole.join("; ")}`,
		);
	}
	await assertWebhookContract();
	await assertPartialMigration(
		PARTIAL_DATABASE,
		previousChanges,
		candidateChanges - previousChanges,
	);
	await assertFreshSchemaEqualsUpgraded(candidateImage);
	console.log(`Seeded release upgrade passed (Heph chat seed: ${hephChatSource}).`);
} catch (error) {
	for (const name of [application, postgres]) {
		printLogs(name);
	}
	throw error;
} finally {
	sweepSandboxes();
	// --volumes removes only these containers' anonymous volumes, such as PostgreSQL's data.
	spawnSync("docker", ["rm", "--force", "--volumes", application, postgres], {
		stdio: "ignore",
	});
	spawnSync("docker", ["network", "rm", network], { stdio: "ignore" });
}
