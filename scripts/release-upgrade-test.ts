import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import {
	appendFileSync,
	mkdtempSync,
	readFileSync,
	readdirSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

import { NEXT_CHANGELOG_DIRECTORY } from "./lib/changelog.ts";
import { readCutoverSeeds } from "./lib/cutover-seeds.ts";
import { applyDatabaseRoles, databaseRolePasswords } from "./lib/database-roles.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { waitForFinalServer } from "./lib/postgres-container.ts";
import { BASELINE_RELEASE } from "./lib/previous-release.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import {
	partialMigration,
	preservationSql,
	type PreservedPeopleIds,
} from "./lib/release-upgrade-fingerprint.ts";
import {
	assertForwardReferenceRefused,
	assertPrefixHistory,
	firstMigration,
	prefixChangelog,
	prefixHistorySql,
} from "./lib/release-upgrade-prefix.ts";
import { parseVersion } from "./lib/release-version.ts";
import { researchConsentFields } from "./lib/research-consent.ts";

const [previousImage = "", candidateImage = "", postgresImage = ""] = process.argv.slice(2);
/** The system key of every run, the previous release's and the candidate's: one installation keeps one key. */
const SYSTEM_KEY = "upgradeTestEncryptionKey01234567";
const CREDENTIAL_KEY = "upgradeTestCredentialKey01234567";

if (previousImage === "" || candidateImage === "" || postgresImage === "") {
	throw new Error(
		"Usage: node scripts/release-upgrade-test.ts <previous-app-image> <candidate-app-image> <postgres-image>",
	);
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
const VALID_PREFIX_DATABASE = "hephaestus_valid_prefix";
const BROKEN_PREFIX_DATABASE = "hephaestus_broken_prefix";
const MIGRATION_STOP_MESSAGE = "the upgrade test stops the migration here";
const HTTP_TIMEOUT_MS = 30_000;
const READINESS_TIMEOUT_MS = 2000;

const runId = `upgrade-${randomUUID().slice(0, 8)}`;
const network = runId;
const postgres = `${runId}-postgres`;
const credentials = databaseRolePasswords();
let application = `${runId}-previous`;

function docker(...args: string[]): string {
	const result = spawnSync("docker", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	if (result.status !== 0) {
		// The arguments carry the run's keys; a failure message names the variables, not the values.
		const shown = args.map((arg) =>
			arg.replace(/^(?<name>HEPHAESTUS_SECURITY_[A-Z_]+)=.*/u, "$<name>=<redacted>"),
		);
		throw new Error(`docker ${shown.join(" ")} failed:\n${result.stdout}${result.stderr}`);
	}
	return (args[0] === "logs" ? result.stdout + result.stderr : result.stdout).trim();
}

async function fetchWithTimeout(input: string, init?: RequestInit): Promise<Response> {
	return fetch(input, { ...init, signal: AbortSignal.timeout(HTTP_TIMEOUT_MS) });
}

function databaseEnvironment(database: string, role: "owner" | "app"): string[] {
	const password =
		role === "owner" ? credentials.DATABASE_OWNER_PASSWORD : credentials.DATABASE_APP_PASSWORD;
	return [
		"--env",
		"SPRING_LIQUIBASE_CONTEXTS=prod",
		"--env",
		`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${database}`,
		"--env",
		`SPRING_DATASOURCE_USERNAME=hephaestus_${role}`,
		"--env",
		`SPRING_DATASOURCE_PASSWORD=${password}`,
		...(role === "owner"
			? ["--env", `SPRING_LIQUIBASE_PASSWORD=${credentials.DATABASE_OWNER_PASSWORD}`]
			: []),
	];
}

/**
 * Both releases support the migrate run mode. Each runtime starts as the application role on the schema it prepared.
 */
function migrate(image: string, database = "hephaestus", changelog?: string) {
	const name = `${runId}-migration`;
	const result = spawnSync(
		"docker",
		[
			"run",
			"--rm",
			"--name",
			name,
			"--network",
			network,
			"--env",
			"SPRING_PROFILES_ACTIVE=e2e,migrate",
			...databaseEnvironment(database, "owner"),
			...(image === previousImage ? ["--env", "HEPHAESTUS_SYNC_NATS_ENABLED=false"] : []),
			// The migration opens the values that the previous release sealed, as the chart's migration Job does.
			"--env",
			`HEPHAESTUS_SECURITY_ENCRYPTION_KEY=${SYSTEM_KEY}`,
			"--env",
			`HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=${CREDENTIAL_KEY}`,
			...(changelog === undefined
				? []
				: [
						"--mount",
						`type=bind,source=${changelog},target=/upgrade-prefix.xml,readonly`,
						"--env",
						"SPRING_LIQUIBASE_CHANGE_LOG=file:/upgrade-prefix.xml",
					]),
			image,
		],
		{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES, timeout: 600_000 },
	);
	if (result.error !== undefined) {
		spawnSync("docker", ["rm", "--force", name], { stdio: "ignore" });
		throw new Error(`Could not run migration with ${image}`, { cause: result.error });
	}
	return result;
}

function migrateRelease(image: string, database = "hephaestus"): void {
	applyDatabaseRoles(postgres, database, credentials);
	const result = migrate(image, database);
	if (result.status !== 0) {
		throw new Error(
			`Migration with ${image} exited with ${String(result.status ?? result.signal)}:\n${result.stdout}${result.stderr}`,
		);
	}
}

/** The previous release's agent image, which its agent runtime checks at start. */
interface Worker {
	readonly agentImage: string;
}

/** The previous release's server and agent runtime in one JVM, as the release runs them. */
function workerEnvironment(worker: Worker | undefined): string[] {
	if (worker === undefined) {
		// Exercise the server/database upgrade without sandboxes: the previous release's worker role and the
		// candidate's agent runtime stay off.
		return ["--env", "HEPHAESTUS_RUNTIME_WORKER_ENABLED=false", "--env", "AGENT_ENABLED=false"];
	}
	return [
		"--env",
		"HEPHAESTUS_RUNTIME_WORKER_ENABLED=true",
		"--env",
		"AGENT_ENABLED=true",
		"--env",
		`HEPHAESTUS_AGENT_IMAGE_REFERENCE=${worker.agentImage}`,
		// The agent runtime claims its sandboxes from Kubernetes and refuses to start without a valid namespace.
		// No cluster answers here, so it starts no sandbox.
		"--env",
		`HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE=${runId}`,
		// Readiness reports practice review down without Git checkout.
		"--env",
		"GIT_CHECKOUT_ENABLED=true",
		"--env",
		"HEPHAESTUS_FABRIC_ROOT=/tmp/hephaestus-git",

		// The buildpack's 10M direct-memory default is too small for the agent runtime's clients.
		"--env",
		"JAVA_TOOL_OPTIONS=-XX:MaxDirectMemorySize=128M",
	];
}

function startApplication(
	name: string,
	image: string,
	database = "hephaestus",
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
		"SPRING_LIQUIBASE_ENABLED=false",
		...databaseEnvironment(database, "app"),
		// Only the released baseline still has a broker client. The candidate opens no broker connection.
		...(image === previousImage ? ["--env", "HEPHAESTUS_SYNC_NATS_ENABLED=false"] : []),
		...workerEnvironment(worker),
		"--env",
		"HEPHAESTUS_WORKSPACE_INIT_DEFAULT=false",
		"--env",
		"HEPHAESTUS_SYNC_RUN_ON_STARTUP=false",
		"--env",
		`HEPHAESTUS_SECURITY_ENCRYPTION_KEY=${SYSTEM_KEY}`,
		"--env",
		`HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY=${CREDENTIAL_KEY}`,
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
 * Where a release answers its API. The server serves it under `/api` since 1.0; an earlier release answered at the
 * root, behind a proxy that removed the prefix. The previous release can be either.
 */
async function apiOf(port: number): Promise<string> {
	const origin = `http://127.0.0.1:${port}`;
	const prefixed = await fetchWithTimeout(`${origin}/api/identity-providers`);
	await prefixed.body?.cancel();
	return prefixed.ok ? `${origin}/api` : origin;
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

async function login(api: string, username: string): Promise<Session> {
	const response = await fetchWithTimeout(`${api}/auth/dev-login`, {
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
	const probe = await fetchWithTimeout(`${api}/user`, {
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

async function completeTransparencyNotice(api: string, session: Session): Promise<void> {
	const status = await fetchWithTimeout(`${api}/user/consent`, {
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
	const completed = await fetchWithTimeout(`${api}/user/consent`, {
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

async function assertCoreReads(api: string, session: Session): Promise<void> {
	const user = await fetchWithTimeout(`${api}/user`, {
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

	const providers = await fetchWithTimeout(`${api}/identity-providers`);
	if (!providers.ok) {
		throw new Error(
			`Core read /identity-providers returned ${providers.status}: ${await providers.text()}`,
		);
	}
	const providerBody: unknown = await providers.json();
	if (!Array.isArray(providerBody) || providerBody.length === 0) {
		throw new Error("Core read /identity-providers returned no providers");
	}
	const workspaces = await fetchWithTimeout(`${api}/workspaces`, {
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

async function seedWorkspace(api: string, session: Session, workspaceSlug: string): Promise<void> {
	const response = await fetchWithTimeout(`${api}/workspaces`, {
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

async function assertInstalledCatalog(api: string, session: Session): Promise<void> {
	const root = await login(api, "root");
	await completeTransparencyNotice(api, root);
	const response = await fetchWithTimeout(`${api}/admin/practice-catalog`, {
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
		`${api}/workspaces/${ADOPTION_WORKSPACE_SLUG}/practices`,
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
	name: "assert-migrated.sql" | "result-parent.sql",
	database = "hephaestus",
): void {
	executeSql(
		`Upgrade fixture ${name}`,
		readFileSync(
			new URL(
				`../server/application/src/test/resources/db/practice-native/${name}`,
				import.meta.url,
			),
			"utf8",
		),
		database,
	);
}

/** Runs SQL in one transaction, and names it as `what` when it fails. */
function executeSql(what: string, input: string, database = "hephaestus"): void {
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
		throw new Error(`${what} failed: ${result.stderr}`);
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

/** The schema fingerprint query, shared with the server's tests (`db/schema-fingerprint.sql`). */
const SCHEMA_FINGERPRINT = readFileSync(
	new URL("../server/application/src/main/resources/db/schema-fingerprint.sql", import.meta.url),
	"utf8",
);

/** Every object that the changelog decides in the application schema, one sorted line each. */
function schemaFingerprint(database: string): string[] {
	const facts = query(SCHEMA_FINGERPRINT, database);
	return facts === "" ? [] : facts.split("\n");
}

/** Starts the candidate once on an empty database and compares its schema with the upgraded one. */
async function assertFreshSchemaEqualsUpgraded(image: string): Promise<void> {
	docker("exec", postgres, "createdb", "--username=root", "hephaestus_fresh");
	const fresh = `${runId}-fresh`;
	try {
		migrateRelease(image, "hephaestus_fresh");
		await waitUntilReady(fresh, startApplication(fresh, image, "hephaestus_fresh"));
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

function copyStoppedFixture(database: string): void {
	docker("exec", postgres, "createdb", "--username=root", "--template=hephaestus", database);
}

function assertRc1MigrationPrefix(): void {
	const next = new URL(`../${NEXT_CHANGELOG_DIRECTORY}/`, import.meta.url);
	const files = readdirSync(next, { withFileTypes: true })
		.filter((entry) => entry.isFile())
		.map((entry) => entry.name);
	const first = firstMigration(files, (file) => readFileSync(new URL(file, next), "utf8"));
	const directory = mkdtempSync(nodePath.join(tmpdir(), "hephaestus-upgrade-prefix-"));
	const changelog = nodePath.join(directory, "prefix.xml");
	try {
		for (const [database, broken] of [
			[BROKEN_PREFIX_DATABASE, true],
			[VALID_PREFIX_DATABASE, false],
		] as const) {
			writeFileSync(changelog, prefixChangelog(first, broken), { mode: 0o644 });
			applyDatabaseRoles(postgres, database, credentials);
			const result = migrate(candidateImage, database, changelog);
			const output = `${result.stdout}${result.stderr}`;
			if (broken) {
				assertForwardReferenceRefused(first, result.status, output);
			} else if (result.status !== 0) {
				throw new Error(`The valid migration prefix failed:\n${output}`);
			}
			assertPrefixHistory(first, query(prefixHistorySql(first), database), broken);
		}
		console.log("Liquibase refused the broken prefix at upgrade-test-forward-reference.");
		console.log(
			`The candidate's first migration, ${first.resource}, applied on ${previousVersion}.`,
		);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

/** A check that passed without testing anything: a warning in the log and a line in the job summary. */
/** One job can run the upgrade from more than one release, so each line names its starting release. */
function summarize(line: string): void {
	const named = `From ${previousVersion}: ${line}`;
	console.log(`::warning::${named}`);
	const summary = process.env.GITHUB_STEP_SUMMARY;
	if (summary !== undefined && summary !== "") {
		appendFileSync(summary, `- ${named}\n`);
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
 * readiness, the core reads, a new workspace, which writes the workspace and its practice catalog, and a review model
 * bound to a workspace. The e2e profile validates every entity against the schema at start. A sandbox claims its own
 * run when it pulls, and no cluster runs here, so no review runs.
 */
async function assertPreviousReleaseRuns(database: string, schema: string): Promise<void> {
	const name = `${runId}-previous-on-${database}`;
	try {
		withFixture(database, () => seedReview(database));
		const port = startApplication(name, previousImage, database, {
			agentImage: previousAgentImage,
		});
		await waitUntilReady(name, port, ["/readyz", "/actuator/health/readiness"]);
		const api = await apiOf(port);
		const session = await login(api, "alice");
		await completeTransparencyNotice(api, session);
		await assertCoreReads(api, session);
		await seedWorkspace(api, session, PREVIOUS_RELEASE_WORKSPACE_SLUG);
		await bindReviewModel(api, session);
	} catch (error) {
		printLogs(name);
		throw error;
	} finally {
		spawnSync("docker", ["rm", "--force", name], { stdio: "ignore" });
	}
	console.log(`The previous release runs on ${schema}.`);
}

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
	executeFixture("result-parent.sql", database);
	query(
		`INSERT INTO membership (workspace_id, account_id, role)
		SELECT 978001, id, 'ADMIN' FROM account WHERE primary_email = 'alice@dev.invalid'`,
		database,
	);
}

async function write(
	api: string,
	session: Session,
	method: "POST" | "PUT" | "PATCH",
	path: string,
	body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
	const response = await fetchWithTimeout(`${api}${path}`, {
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
 * workspace, through the previous release's own API.
 */
async function bindReviewModel(api: string, alice: Session): Promise<void> {
	const root = await login(api, "root");
	await completeTransparencyNotice(api, root);
	const connection = await write(api, root, "POST", "/admin/llm/connections", {
		displayName: "Upgrade test",
		baseUrl: "http://127.0.0.1:9/v1",
		apiProtocol: "openai-completions",
		apiKey: "upgrade-test-key",
		enabled: true,
	});
	const model = await write(
		api,
		root,
		"POST",
		`/admin/llm/connections/${String(connection.id)}/models`,
		{ displayName: "Upgrade model", upstreamModelId: "upgrade-model" },
	);
	const modelId = Number(model.id);
	await write(api, root, "PUT", `/admin/llm/models/${modelId}/price`, {
		pricingMode: "NO_CHARGE",
		note: "Self-hosted test model",
	});
	await write(api, root, "PATCH", `/admin/llm/models/${modelId}`, { enabled: true });
	await write(api, root, "PUT", `/admin/llm/models/${modelId}/sharing`, { visibility: "PUBLIC" });
	await write(
		api,
		alice,
		"PUT",
		`/workspaces/${REVIEW_WORKSPACE}/model-assignments/PRACTICE_REVIEW`,
		{
			instanceModelId: modelId,
			enabled: true,
		},
	);
}

/**
 * Stops the candidate's migration after about half of its new change sets, as a failed change set stops it: the failed
 * change set rolls back. The stop is an event trigger on the next change set's first DDL statement, so a change set
 * without DDL can pass it. A trigger on the history insert would not do: Liquibase 5.0.x commits a change set before
 * its history row. The previous release must run on that prefix, and the migration must complete when it runs again.
 * `database` is a copy of the seeded database from before the upgrade.
 *
 * The trigger's function lives in its own schema. The 1.0 baseline synchronization refuses a database on 1.0.0-rc.1
 * whose application schema differs from the pinned fingerprint, and that fingerprint covers every function in `public`.
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
		`CREATE SCHEMA upgrade_test;
		CREATE FUNCTION upgrade_test.stop_migration() RETURNS event_trigger LANGUAGE plpgsql AS $$
		BEGIN
			IF (SELECT count(*) FROM public.databasechangelog) >= ${before + applied} THEN
				RAISE EXCEPTION '${MIGRATION_STOP_MESSAGE}';
			END IF;
		END $$;
		CREATE EVENT TRIGGER stop_upgrade_test_migration ON ddl_command_start
			EXECUTE FUNCTION upgrade_test.stop_migration();`,
		database,
	);
	applyDatabaseRoles(postgres, database, credentials);
	let stopped: ReturnType<typeof migrate>;
	try {
		stopped = migrate(candidateImage, database);
	} finally {
		query(
			"DROP EVENT TRIGGER stop_upgrade_test_migration; DROP SCHEMA upgrade_test CASCADE;",
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
	migrateRelease(candidateImage, database);
	const completed = appliedChangeCount(database) - before;
	if (completed !== pending) {
		throw new Error(`The repeated migration applied ${completed} of ${pending} new change sets`);
	}
	console.log("The candidate's migration completed when it ran again.");
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

	await waitForFinalServer(postgres);

	migrateRelease(previousImage);
	let port = startApplication(application, previousImage);
	// A previous release without the management port answers readiness only on the actuator path.
	await waitUntilReady(application, port, ["/readyz", "/actuator/health/readiness"]);
	let api = await apiOf(port);
	const previousSession = await login(api, "alice");
	await completeTransparencyNotice(api, previousSession);
	await login(api, "root");
	linkWorkspaceIdentity();
	await seedWorkspace(api, previousSession, WORKSPACE_SLUG);
	await assertCoreReads(api, previousSession);
	docker("stop", "--time", "30", application);
	docker("rm", application);
	// The legacy rows that each conversion seed writes on the previous release's schema.
	const seeds = readCutoverSeeds();
	for (const seed of seeds) {
		executeSql(`Conversion seed ${seed.name}`, seed.seed);
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
	copyStoppedFixture(PARTIAL_DATABASE);
	const qualifiesRc1Prefix = `v${previousVersion}` === BASELINE_RELEASE;
	if (qualifiesRc1Prefix) {
		copyStoppedFixture(BROKEN_PREFIX_DATABASE);
		copyStoppedFixture(VALID_PREFIX_DATABASE);
		assertRc1MigrationPrefix();
	} else {
		summarize(`Migration prefix qualification applies to ${BASELINE_RELEASE}.`);
	}
	migrateRelease(candidateImage);
	application = `${runId}-candidate`;
	port = startApplication(application, candidateImage);
	await waitUntilReady(application, port);
	api = await apiOf(port);
	const upgradedData = dataFingerprint(preserved);
	executeFixture("assert-migrated.sql");
	for (const seed of seeds) {
		executeSql(`Expected disposition of conversion seed ${seed.name}`, seed.expect);
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
	if (qualifiesRc1Prefix) {
		migrateRelease(candidateImage, VALID_PREFIX_DATABASE);
		const history = "SELECT filename, id, author FROM databasechangelog ORDER BY 1, 2, 3";
		if (query(history, VALID_PREFIX_DATABASE) !== query(history)) {
			throw new Error("The valid migration prefix did not resume to the full migration history");
		}
		console.log("The candidate completed the migration from the valid prefix.");
	}
	const candidateSession = await login(api, "alice");
	await completeTransparencyNotice(api, candidateSession);
	await assertCoreReads(api, candidateSession);

	await seedWorkspace(api, candidateSession, ADOPTION_WORKSPACE_SLUG);
	await assertInstalledCatalog(api, candidateSession);
	const installedPractices = workspacePracticeCount(ADOPTION_WORKSPACE_SLUG);
	docker("restart", "--time", "30", application);
	port = publishedPort(application);
	await waitUntilReady(application, port);
	api = await apiOf(port);
	const restartedSession = await login(api, "alice");
	await assertInstalledCatalog(api, restartedSession);
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
	await assertPartialMigration(
		PARTIAL_DATABASE,
		previousChanges,
		candidateChanges - previousChanges,
	);
	await assertFreshSchemaEqualsUpgraded(candidateImage);
	console.log("Seeded release upgrade passed.");
} catch (error) {
	for (const name of [application, postgres]) {
		printLogs(name);
	}
	throw error;
} finally {
	// --volumes removes only these containers' anonymous volumes, such as PostgreSQL's data.
	spawnSync("docker", ["rm", "--force", "--volumes", application, postgres], {
		stdio: "ignore",
	});
	spawnSync("docker", ["network", "rm", network], { stdio: "ignore" });
}
