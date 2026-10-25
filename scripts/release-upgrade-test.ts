import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import {
	PEOPLE_LAYOUT_SQL,
	peopleLayout,
	preservationSql,
	type PeopleLayout,
	type PreservedPeopleIds,
} from "./lib/release-upgrade-fingerprint.ts";
import { researchConsentFields } from "./lib/research-consent.ts";

const [
	previousImage = "",
	candidateImage = "",
	postgresImage = "",
	upgradePath = "latest",
	...options
] = process.argv.slice(2);

const USAGE =
	"Usage: node scripts/release-upgrade-test.ts <previous-app-image> <candidate-app-image> <postgres-image> [latest|baseline|refusal] [--require-seed=heph-chat]";

if (previousImage === "" || candidateImage === "" || postgresImage === "") {
	throw new Error(USAGE);
}

if (!["latest", "baseline", "refusal"].includes(upgradePath)) {
	throw new Error(`Unknown upgrade path: ${upgradePath}`);
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
if (requiredSeeds.size > 0 && upgradePath !== "latest") {
	throw new Error("--require-seed applies only to the latest upgrade path");
}

const CUT_POINT_MESSAGE =
	"install v0.77.4 and start it once, then follow the baseline synchronization runbook";

const WORKSPACE_SLUG = "upgrade-fixture";
const ADOPTION_WORKSPACE_SLUG = "upgrade-adoption-fixture";
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

function startApplication(
	name: string,
	image: string,
	database = "hephaestus",
	migratesAtStart = true,
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
		"--env",
		// Exercise the server/database upgrade without optional worker infrastructure.
		"HEPHAESTUS_RUNTIME_WORKER_ENABLED=false",
		"--env",
		"HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED=false",
		"--env",
		"AGENT_ENABLED=false",
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

function publishedPort(name: string): number {
	const mapping = docker("port", name, "8080/tcp");
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

function linkWorkspaceIdentity(layout: PeopleLayout): void {
	// Native entity initializers and @CreationTimestamp are not database defaults, so every
	// non-null identity column is written.
	const link =
		layout === "legacy"
			? `INSERT INTO identity_link
		   (account_id, provider_id, subject, external_actor_id, username_at_signup, linked_via)
		 SELECT fixture.account_id, created.provider_id, created.native_id::text, created.id,
		        'account:' || fixture.account_id, 'MANUAL_LINK'`
			: `INSERT INTO identity
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

function dataFingerprint(layout: PeopleLayout, preserved?: PreservedPeopleIds): string {
	return query(preservationSql(layout, preserved));
}

const rows = (table: string) => {
	const value = query(`SELECT id FROM ${table} ORDER BY id`);
	return value === "" ? [] : value.split("\n");
};

function preservedPeople(layout: PeopleLayout): PreservedPeopleIds {
	return {
		accounts: rows("account"),
		identities: rows(layout === "legacy" ? "identity_link" : "identity"),
	};
}

function executeFixture(
	name: "released-seed.sql" | "assert-migrated.sql" | "assert-upgraded.sql",
	directory: "practice-cutover" | "heph-upgrade" = "practice-cutover",
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
			"--dbname=hephaestus",
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

function count(sql: string): number {
	const value = docker(
		"exec",
		postgres,
		"psql",
		"--username=root",
		"--dbname=hephaestus",
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

function appliedChangeCount(): number {
	return count("SELECT count(*) FROM databasechangelog;");
}

// Use the target image's bundled Liquibase and resources, not a separately versioned CLI.
function baselineSynchronization(image: string) {
	return spawnSync(
		"docker",
		[
			"run",
			"--rm",
			"--name",
			`${runId}-sync`,
			"--network",
			network,
			"--entrypoint",
			"/cnb/lifecycle/launcher",
			image,
			"--",
			"java",
			"-cp",
			"runner.jar:lib/*",
			"liquibase.integration.commandline.Main",
			"--changeLogFile=db/master.xml",
			"--url=jdbc:postgresql://postgres:5432/hephaestus",
			"--username=root",
			"--password=root",
			"--contexts=prod",
			"changeLogSyncToTag",
			"baseline_v0_77_4",
		],
		{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES, timeout: 180_000 },
	);
}

function historyFingerprint(): string {
	return docker(
		"exec",
		postgres,
		"psql",
		"--username=root",
		"--dbname=hephaestus",
		"--tuples-only",
		"--no-align",
		"--set=ON_ERROR_STOP=1",
		"--command",
		"SELECT row_to_json(history)::text FROM databasechangelog history ORDER BY orderexecuted;",
	);
}

function assertRefusal(image: string, seededData: string): void {
	if (count("SELECT count(*) FROM databasechangelog WHERE id = '1788679885460-1';") !== 0) {
		throw new Error("Refusal fixture unexpectedly reached v0.77.4");
	}
	const history = historyFingerprint();
	const result = baselineSynchronization(image);
	if (
		result.status === null ||
		result.status === 0 ||
		!`${result.stdout}${result.stderr}`.includes(CUT_POINT_MESSAGE)
	) {
		throw new Error(`Expected cut-point refusal from changeLogSyncToTag:
${result.stdout}${result.stderr}`);
	}
	if (historyFingerprint() !== history || dataFingerprint("legacy") !== seededData) {
		throw new Error("Refused synchronization changed migration history or seeded data");
	}

	const refused = migrate(image);
	if (
		refused.status === null ||
		refused.status === 0 ||
		!`${refused.stdout}${refused.stderr}`.includes(CUT_POINT_MESSAGE)
	) {
		throw new Error(`Expected cut-point refusal from the migration (exit ${String(refused.status)}):
${refused.stdout}${refused.stderr}`);
	}
	if (historyFingerprint() !== history || dataFingerprint("legacy") !== seededData) {
		throw new Error("Refused migration changed migration history or seeded data");
	}
	if (count("SELECT count(*) FROM databasechangeloglock WHERE locked;") !== 0) {
		throw new Error("Refused upgrade left the Liquibase lock held");
	}
	console.log("Pinned v0.76.0 update and synchronization refusal passed.");
}

function synchronizeBaseline(image: string): void {
	const baselineFile = "db/changelog/0000000000000_baseline_v0_77_4.xml";
	if (
		count(`SELECT count(*) FROM databasechangelog
			WHERE id = 'baseline_v0_77_4-tag' AND author = 'hephaestus-release'
			AND filename = '${baselineFile}' AND tag = 'baseline_v0_77_4';`) === 1
	) {
		return;
	}

	if (
		count(`SELECT count(*) FROM databasechangelog
			WHERE id = '1788679885460-1' AND author = 'hephaestus'
			AND filename = 'db/changelog/1788679885460_changelog.xml'
			AND md5sum = '9:658c0b7abed980fcd0e8142337bec5be'
			AND exectype IN ('EXECUTED', 'MARK_RAN');`) !== 1
	) {
		throw new Error(
			"The previous release has not reached the verified v0.77.4 baseline cut-point.",
		);
	}

	const result = baselineSynchronization(image);
	if (result.status !== 0) {
		throw new Error(`Baseline synchronization failed:\n${result.stdout}${result.stderr}`);
	}

	if (
		count(`SELECT count(*) FROM databasechangelog
			WHERE id = 'baseline_v0_77_4-tag' AND author = 'hephaestus-release'
			AND filename = '${baselineFile}' AND tag = 'baseline_v0_77_4';`) !== 1
	) {
		throw new Error("Baseline synchronization did not record the expected tag.");
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
	const layout = peopleLayout(query(PEOPLE_LAYOUT_SQL));
	console.log(`Previous release people layout: ${layout}.`);
	if (upgradePath !== "latest" && layout !== "legacy") {
		throw new Error(
			`The pinned ${upgradePath} path requires the legacy people layout, found ${layout}`,
		);
	}
	const previousSession = await login(port, "alice");
	await completeTransparencyNotice(port, previousSession);
	await login(port, "root");
	linkWorkspaceIdentity(layout);
	await seedWorkspace(port, previousSession, WORKSPACE_SLUG);
	await assertCoreReads(port, previousSession);
	docker("stop", "--time", "30", application);
	docker("rm", application);
	// The released-schema marker survives the cutover, so only a legacy layout takes the seed.
	const exactMainSource =
		upgradePath === "latest" &&
		layout === "legacy" &&
		count("SELECT count(*) FROM databasechangelog WHERE id='1791941746633-2'") === 1;
	if (exactMainSource) {
		executeFixture("released-seed.sql");
	}
	const hephChatSource = upgradePath === "latest" && keepsHephChatHistory();
	if (hephChatSource) {
		executeFixture("released-seed.sql", "heph-upgrade");
	} else if (requiredSeeds.has("heph-chat")) {
		throw new Error(
			"The required heph-chat seed was not admitted: the previous release keeps no Heph chat history",
		);
	}
	const preserved = preservedPeople(layout);
	// Legacy stays unfiltered so a refused start that adds people is still detected.
	const seededData =
		layout === "legacy" ? dataFingerprint("legacy") : dataFingerprint("native", preserved);
	for (const kind of ["account", "identity", "user", "workspace", "membership", "connection"]) {
		if (!seededData.includes(`${kind}|`)) {
			throw new Error(`Previous release did not seed ${kind} data`);
		}
	}
	const previousPractices = workspacePracticeCount(WORKSPACE_SLUG);
	const previousChanges = appliedChangeCount();

	if (upgradePath === "refusal") {
		assertRefusal(candidateImage, seededData);
	} else {
		if (
			upgradePath === "baseline" &&
			count("SELECT count(*) FROM databasechangelog WHERE id = 'baseline_v0_77_4-tag';") !== 0
		) {
			throw new Error("Pinned v0.77.4 fixture already has a baseline tag");
		}
		synchronizeBaseline(candidateImage);
		migrateCandidate(candidateImage);
		application = `${runId}-candidate`;
		port = startApplication(application, candidateImage, "hephaestus", false);
		await waitUntilReady(application, port);
		if (peopleLayout(query(PEOPLE_LAYOUT_SQL)) !== "native") {
			throw new Error("Candidate did not reach the native people layout");
		}
		const upgradedData = dataFingerprint("native", preserved);
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

		if (upgradePath === "latest") {
			await assertFreshSchemaEqualsUpgraded(candidateImage);
		}
		console.log(
			`Seeded ${upgradePath} release upgrade passed (origin/main review seed: ${exactMainSource}, Heph chat seed: ${hephChatSource}).`,
		);
	}
} catch (error) {
	for (const name of [application, postgres]) {
		try {
			console.error(`\n--- ${name} logs ---\n${docker("logs", name)}`);
		} catch {
			// Preserve the upgrade failure if diagnostic collection also fails.
		}
	}
	throw error;
} finally {
	// --volumes removes only these containers' anonymous volumes, such as PostgreSQL's data.
	spawnSync("docker", ["rm", "--force", "--volumes", application, `${runId}-sync`, postgres], {
		stdio: "ignore",
	});
	spawnSync("docker", ["network", "rm", network], { stdio: "ignore" });
}
