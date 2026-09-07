import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const [previousImage, candidateImage, postgresImage] = process.argv.slice(2);

if (!previousImage || !candidateImage || !postgresImage) {
	throw new Error(
		"Usage: node scripts/release-upgrade-test.ts <previous-app-image> <candidate-app-image> <postgres-image>",
	);
}

const WORKSPACE_SLUG = "upgrade-fixture";
const ADOPTION_WORKSPACE_SLUG = "upgrade-adoption-fixture";
const HTTP_TIMEOUT_MS = 30_000;
const READINESS_TIMEOUT_MS = 2_000;

const runId = `upgrade-${randomUUID().slice(0, 8)}`;
const network = runId;
const postgres = `${runId}-postgres`;
let application = `${runId}-previous`;

function docker(...args: string[]): string {
	const result = spawnSync("docker", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	if (result.status !== 0) {
		throw new Error(`docker ${args.join(" ")} failed:\n${result.stdout}${result.stderr}`);
	}
	return result.stdout.trim();
}

function fetchWithTimeout(input: string, init?: RequestInit): Promise<Response> {
	return fetch(input, { ...init, signal: AbortSignal.timeout(HTTP_TIMEOUT_MS) });
}

function startApplication(name: string, image: string): number {
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
		"SPRING_LIQUIBASE_CONTEXTS=prod",
		"--env",
		"SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/hephaestus",
		"--env",
		"SPRING_DATASOURCE_USERNAME=root",
		"--env",
		"SPRING_DATASOURCE_PASSWORD=root",
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
	const mapping = docker("port", name, "8080/tcp");
	const port = Number(mapping.slice(mapping.lastIndexOf(":") + 1));
	if (!Number.isInteger(port)) throw new Error(`Could not parse application port from ${mapping}`);
	return port;
}

async function waitUntilReady(name: string, port: number): Promise<void> {
	const deadline = Date.now() + 180_000;
	while (Date.now() < deadline) {
		const state = docker("inspect", "--format", "{{.State.Status}}", name);
		if (state !== "running") throw new Error(`${name} stopped during startup`);
		try {
			const response = await fetch(`http://127.0.0.1:${port}/actuator/health/readiness`, {
				signal: AbortSignal.timeout(READINESS_TIMEOUT_MS),
			});
			if (response.ok) return;
		} catch {
			// The endpoint is unavailable while the container starts.
		}
		await sleep(2_000);
	}
	throw new Error(`${name} did not become ready within 180 seconds`);
}

async function login(port: number, username: string): Promise<string> {
	const response = await fetchWithTimeout(`http://127.0.0.1:${port}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({
			username,
			displayName: `Upgrade ${username}`,
			admin: username === "root",
		}),
	});
	if (response.status !== 204)
		throw new Error(`Dev login returned ${response.status}: ${await response.text()}`);
	const cookie = response.headers
		.getSetCookie()
		.find((value) => value.slice(0, value.indexOf("=")).endsWith("HEPHAESTUS_AT"))
		?.split(";", 1)[0];
	if (!cookie) throw new Error("Dev login did not return an authentication cookie");
	return cookie;
}

async function completeTransparencyNotice(port: number, cookie: string): Promise<void> {
	const status = await fetchWithTimeout(`http://127.0.0.1:${port}/user/consent`, {
		headers: { cookie },
	});
	if (!status.ok)
		throw new Error(`Consent status returned ${status.status}: ${await status.text()}`);
	const statusBody: unknown = await status.json();
	if (
		typeof statusBody !== "object" ||
		statusBody === null ||
		!("noticeVersion" in statusBody) ||
		typeof statusBody.noticeVersion !== "string"
	)
		throw new Error("Consent status did not return the current notice version");
	if ("completed" in statusBody && statusBody.completed === true) return;
	const completed = await fetchWithTimeout(`http://127.0.0.1:${port}/user/consent`, {
		method: "PUT",
		headers: { "content-type": "application/json", cookie },
		body: JSON.stringify({
			noticeVersion: statusBody.noticeVersion,
			termsAccepted: true,
			participateInResearch: false,
		}),
	});
	if (!completed.ok)
		throw new Error(
			`Completing the transparency notice returned ${completed.status}: ${await completed.text()}`,
		);
}

async function assertCoreReads(port: number, cookie: string): Promise<void> {
	const user = await fetchWithTimeout(`http://127.0.0.1:${port}/user`, { headers: { cookie } });
	if (!user.ok) throw new Error(`Core read /user returned ${user.status}: ${await user.text()}`);
	const userBody: unknown = await user.json();
	if (
		typeof userBody !== "object" ||
		userBody === null ||
		!("displayName" in userBody) ||
		userBody.displayName !== "Upgrade alice"
	)
		throw new Error("Core read /user did not return the seeded account");

	const providers = await fetchWithTimeout(`http://127.0.0.1:${port}/identity-providers`);
	if (!providers.ok)
		throw new Error(
			`Core read /identity-providers returned ${providers.status}: ${await providers.text()}`,
		);
	const providerBody: unknown = await providers.json();
	if (!Array.isArray(providerBody) || providerBody.length === 0)
		throw new Error("Core read /identity-providers returned no providers");
	const workspaces = await fetchWithTimeout(`http://127.0.0.1:${port}/workspaces`, {
		headers: { cookie },
	});
	if (!workspaces.ok)
		throw new Error(
			`Core read /workspaces returned ${workspaces.status}: ${await workspaces.text()}`,
		);
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
	)
		throw new Error("Core read /workspaces did not return the seeded workspace");
}

async function seedWorkspace(port: number, cookie: string, workspaceSlug: string): Promise<void> {
	const response = await fetchWithTimeout(`http://127.0.0.1:${port}/workspaces`, {
		method: "POST",
		headers: { "content-type": "application/json", cookie },
		body: JSON.stringify({
			workspaceSlug,
			displayName: "Upgrade Fixture",
			accountLogin: workspaceSlug,
			accountType: "ORG",
			kind: "GITHUB",
			personalAccessToken: "upgrade-fixture-token",
		}),
	});
	if (response.status !== 201)
		throw new Error(`Workspace seed returned ${response.status}: ${await response.text()}`);
}

async function adoptCatalogPractice(port: number, cookie: string): Promise<void> {
	const catalog = `http://127.0.0.1:${port}/workspaces/${ADOPTION_WORKSPACE_SLUG}/practice-catalog/adoption`;
	const offered = await fetchWithTimeout(catalog, {
		headers: { cookie },
	});
	if (!offered.ok)
		throw new Error(`Adoptable practices returned ${offered.status}: ${await offered.text()}`);
	const entries = asArray(await offered.json(), "Adoptable practices").map((entry, index) => {
		const summary = asRecord(entry, `Adoptable practices[${index}]`);
		const availability = asString(
			summary.availability,
			`Adoptable practices[${index}].availability`,
		);
		if (
			availability !== "AVAILABLE" &&
			availability !== "ADOPTED" &&
			availability !== "SLUG_CONFLICT"
		)
			throw new Error(
				`Adoptable practices[${index}].availability has unexpected value: ${availability}`,
			);
		return {
			availability,
			slug: asString(summary.slug, `Adoptable practices[${index}].slug`),
		};
	});
	const practice = entries.find((entry) => entry.availability === "AVAILABLE");
	if (!practice) throw new Error("Candidate returned no practice available for adoption");

	const url = `${catalog}/${encodeURIComponent(practice.slug)}`;
	const preview = await fetchWithTimeout(url, {
		headers: { cookie },
	});
	if (!preview.ok)
		throw new Error(`Adoption preview returned ${preview.status}: ${await preview.text()}`);
	const validator = preview.headers.get("etag");
	if (!validator) throw new Error("Adoption preview returned no ETag to send as If-Match");
	const adopted = await fetchWithTimeout(url, {
		method: "POST",
		headers: { cookie, "if-match": validator },
	});
	if (adopted.status !== 201)
		throw new Error(
			`Adopting from the catalog returned ${adopted.status}: ${await adopted.text()}`,
		);
}

function linkWorkspaceIdentity(): void {
	docker(
		"exec",
		postgres,
		"psql",
		"--username=root",
		"--dbname=hephaestus",
		"--set=ON_ERROR_STOP=1",
		"--command",
		`WITH fixture AS (
		   SELECT account.id AS account_id, identity_provider.id AS provider_id
		   FROM account CROSS JOIN identity_provider
		   WHERE account.primary_email = 'alice@dev.invalid'
		   ORDER BY identity_provider.id LIMIT 1
		 ), created AS (
		   INSERT INTO "user" (provider_id, native_id, login, avatar_url, html_url, type)
		   SELECT provider_id, 424242, 'account:' || account_id, 'https://example.invalid/avatar',
		          'https://example.invalid/user', 'USER'
		   FROM fixture
		   RETURNING id, provider_id
		 )
		 INSERT INTO identity_link
		   (account_id, provider_id, subject, external_actor_id, username_at_signup, linked_via)
		 SELECT fixture.account_id, created.provider_id, 'upgrade-fixture', created.id,
		        'account:' || fixture.account_id, 'MANUAL_LINK'
		 FROM fixture CROSS JOIN created;`,
	);
}

function dataFingerprint(): string {
	return docker(
		"exec",
		postgres,
		"psql",
		"--username=root",
		"--dbname=hephaestus",
		"--tuples-only",
		"--no-align",
		"--command",
		`SELECT kind || '|' || value
		 FROM (
		   SELECT 'account' AS kind,
		          concat_ws('|', id, display_name, app_role, status) AS value
		   FROM account
		   UNION ALL
		   SELECT 'identity', concat_ws('|', id, account_id, subject, username_at_signup, linked_via)
		   FROM identity_link
		   UNION ALL
		   SELECT 'user', concat_ws('|', id, provider_id, native_id, login, type)
		   FROM "user"
		   UNION ALL
		   SELECT 'workspace', concat_ws('|', w.id,
		          coalesce(to_jsonb(w)->>'workspace_slug', to_jsonb(w)->>'slug'),
		          w.display_name, w.account_login, w.account_type, w.status)
		   FROM workspace w
		   UNION ALL
		   SELECT 'membership', concat_ws('|', workspace_id, user_id, role)
		   FROM workspace_membership
		   UNION ALL
		   SELECT 'connection', concat_ws('|', id, workspace_id, kind, state)
		   FROM connection
		 ) fixture
		 ORDER BY kind, value;`,
	);
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
	if (!/^[0-9]+$/.test(value))
		throw new Error(`Expected an integer query result, received: ${value}`);
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

function appliedChangeCount(): number {
	return count("SELECT count(*) FROM databasechangelog;");
}

function synchronizeBaseline(image: string): void {
	const baselineFile = "db/changelog/0000000000000_baseline_v0_77_4.xml";
	if (
		count(`SELECT count(*) FROM databasechangelog
			WHERE id = 'baseline_v0_77_4-tag' AND author = 'hephaestus-release'
			AND filename = '${baselineFile}' AND tag = 'baseline_v0_77_4';`) === 1
	)
		return;

	if (
		count(`SELECT count(*) FROM databasechangelog
			WHERE id = '1788679885460-1' AND author = 'hephaestus'
			AND filename = 'db/changelog/1788679885460_changelog.xml'
			AND md5sum = '9:658c0b7abed980fcd0e8142337bec5be'
			AND exectype IN ('EXECUTED', 'MARK_RAN');`) !== 1
	)
		throw new Error(
			"The previous release has not reached the verified v0.77.4 baseline cut-point.",
		);

	// Use the candidate's bundled Liquibase and resources, not a separately versioned CLI.
	docker(
		"run",
		"--rm",
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
	);
	if (
		count(`SELECT count(*) FROM databasechangelog
			WHERE id = 'baseline_v0_77_4-tag' AND author = 'hephaestus-release'
			AND filename = '${baselineFile}' AND tag = 'baseline_v0_77_4';`) !== 1
	)
		throw new Error("Baseline synchronization did not record the expected tag.");
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

	for (let attempt = 0; attempt < 60; attempt++) {
		try {
			docker("exec", postgres, "pg_isready", "--username=root", "--dbname=hephaestus");
			break;
		} catch {
			if (attempt === 59) throw new Error("PostgreSQL did not become ready");
			await sleep(1_000);
		}
	}

	let port = startApplication(application, previousImage);
	await waitUntilReady(application, port);
	const previousCookie = await login(port, "alice");
	await completeTransparencyNotice(port, previousCookie);
	await login(port, "root");
	linkWorkspaceIdentity();
	await seedWorkspace(port, previousCookie, WORKSPACE_SLUG);
	await assertCoreReads(port, previousCookie);
	const seededData = dataFingerprint();
	for (const kind of ["account", "identity", "user", "workspace", "membership", "connection"]) {
		if (!seededData.includes(`${kind}|`))
			throw new Error(`Previous release did not seed ${kind} data`);
	}
	const previousPractices = workspacePracticeCount(WORKSPACE_SLUG);
	const previousChanges = appliedChangeCount();

	docker("stop", "--time", "30", application);
	docker("rm", application);
	synchronizeBaseline(candidateImage);
	application = `${runId}-candidate`;
	port = startApplication(application, candidateImage);
	await waitUntilReady(application, port);
	const upgradedData = dataFingerprint();
	if (upgradedData !== seededData)
		throw new Error("Seeded application data changed during upgrade");
	const upgradedPractices = workspacePracticeCount(WORKSPACE_SLUG);
	if (upgradedPractices < previousPractices)
		throw new Error(
			`Workspace practices shrank during upgrade: before=${previousPractices}, after=${upgradedPractices}`,
		);
	const candidateChanges = appliedChangeCount();
	if (candidateChanges < previousChanges)
		throw new Error(
			`Liquibase history shrank during upgrade: before=${previousChanges}, after=${candidateChanges}`,
		);
	const candidateCookie = await login(port, "alice");
	await completeTransparencyNotice(port, candidateCookie);
	await assertCoreReads(port, candidateCookie);

	await seedWorkspace(port, candidateCookie, ADOPTION_WORKSPACE_SLUG);
	const practicesBeforeAdoption = workspacePracticeCount(ADOPTION_WORKSPACE_SLUG);
	if (practicesBeforeAdoption !== 0)
		throw new Error(`New workspace unexpectedly started with ${practicesBeforeAdoption} practices`);
	await adoptCatalogPractice(port, candidateCookie);
	const practicesAfterAdoption = workspacePracticeCount(ADOPTION_WORKSPACE_SLUG);
	if (practicesAfterAdoption !== 1)
		throw new Error(`Adoption created ${practicesAfterAdoption} practices instead of one`);

	console.log("Seeded previous-release upgrade passed.");
} catch (error) {
	for (const name of [application, postgres]) {
		try {
			console.error(`\n--- ${name} logs ---\n${docker("logs", name)}`);
		} catch {
			// Containers created after the failure point have no logs.
		}
	}
	throw error;
} finally {
	spawnSync("docker", ["rm", "--force", application, postgres], { stdio: "ignore" });
	spawnSync("docker", ["network", "rm", network], { stdio: "ignore" });
}
