import path from "node:path";
import process from "node:process";
import { parseArgs } from "node:util";

import { Client } from "pg";

import { isLoopbackHost, positivePort, readEnvFile } from "./lib/env.ts";
import { isRecord, parseJson } from "./lib/json.ts";
import { rewindRevisions, workspaceToSeed, writeOrRewind } from "./lib/practices-demo-database.ts";
import {
	type ArtifactRef,
	type Bucket,
	type Citation,
	DEVELOPERS,
	FIRST_JOB,
	ID_PREFIX,
	LOGIN_PREFIX,
	NATIVE_ID_BASE,
	type Outcome,
	READER_RUNS,
	type Severity,
	SKIPPERS_PER_PRACTICE,
	SPLITS,
	TABLE,
	bucketOf,
	daysAgo,
	isProblem,
	readerCards,
	seedId,
} from "./lib/practices-demo.ts";
import { completeTransparencyNotice } from "./lib/research-consent.ts";

/**
 * Writes the practices demo (`scripts/lib/practices-demo.ts`) into the development database, or
 * removes it.
 *
 *     node scripts/seed-practices-across-the-workspace.ts          # remove the seed's rows, then insert them
 *     node scripts/seed-practices-across-the-workspace.ts remove   # remove the seed's rows only
 *
 * What it writes, its flags, and its safety checks: docs/contributor/local-development.mdx
 * § Seeding the practices demo. The seed keeps the ids of the practice revisions the server appended
 * for it on its first job, so a removal rewinds those and no revision a real review appended. A seed
 * that fails after the server appended them rewinds them at once.
 */

const { values: settings, positionals } = parseArgs({
	options: {
		workspace: { type: "string", default: process.env.SEED_WORKSPACE_SLUG ?? "hephaestustest" },
		"pull-request-repository": {
			type: "string",
			default: process.env.SEED_PULL_REQUEST_REPOSITORY ?? "HephaestusTest/practice-validation",
		},
		"issue-repository": {
			type: "string",
			default: process.env.SEED_ISSUE_REPOSITORY ?? "HephaestusTest/MaxTestRepo",
		},
		reader: { type: "string", default: process.env.SEED_READER_LOGIN ?? "ValentinGruener" },
	},
	allowPositionals: true,
});
const WORKSPACE_SLUG = settings.workspace;
const READER_LOGIN = settings.reader;
const REPOSITORY = {
	"scm.pull_request": settings["pull-request-repository"],
	"scm.issue": settings["issue-repository"],
} as const;
const ISSUE_TYPE = { "scm.pull_request": "PULL_REQUEST", "scm.issue": "ISSUE" } as const;
/** How many pieces of work one synthetic developer's runs need: up to seven pull requests and four issues. */
const POOL_MINIMUM = { "scm.pull_request": 7, "scm.issue": 4 } as const;

type Kind = ArtifactRef["kind"];

interface Artifact {
	id: number;
	number: number;
	title: string;
	url: string;
	repository: string;
	kind: Kind;
}

interface SeedPractice {
	id: number;
	slug: string;
	revisionId: number;
	groupSlug: string;
	kind: Kind;
}

interface DevServer {
	server: string;
	token: string;
}

/** One practice's revision as the server pins it for a review. */
interface PinnedRevision {
	slug: string;
	revisionId: number;
	revisionNumber: number;
	appended: boolean;
}

const isPinnedRevision = (value: unknown): value is PinnedRevision =>
	isRecord(value) &&
	typeof value.slug === "string" &&
	typeof value.revisionId === "number" &&
	typeof value.revisionNumber === "number" &&
	typeof value.appended === "boolean";

/** The practices a pull request or issue review observes in a group the demo splits. */
async function seedPractices(client: Client, workspaceId: number): Promise<SeedPractice[]> {
	const rows = await client.query<{
		id: number;
		slug: string;
		revision_id: number | null;
		group_slug: string;
		applies_to: Kind;
	}>(
		`SELECT p.id, p.slug, p.current_revision_id AS revision_id, g.slug AS group_slug, p.applies_to
		 FROM practice p JOIN practice_group g ON g.id = p.practice_group_id
		 WHERE p.workspace_id = $1
		   AND p.applies_to IN ('scm.pull_request', 'scm.issue')
		   AND (p.autonomy IS NULL OR p.autonomy <> 'OFF')`,
		[workspaceId],
	);
	return rows.rows.flatMap((row) =>
		row.revision_id === null || !(row.group_slug in SPLITS)
			? []
			: [
					{
						id: row.id,
						slug: row.slug,
						revisionId: row.revision_id,
						groupSlug: row.group_slug,
						kind: row.applies_to,
					},
				],
	);
}

/** Every synced pull request or issue of one kind in its repository, by number. */
async function artifactsOf(client: Client, kind: Kind): Promise<Artifact[]> {
	const repository = REPOSITORY[kind];
	const rows = await client.query<{ id: number; number: number; title: string; html_url: string }>(
		`SELECT i.id, i.number, i.title, i.html_url FROM issue i
		 JOIN repository r ON r.id = i.repository_id
		 WHERE r.name_with_owner = $1 AND i.issue_type = $2
		 ORDER BY i.number`,
		[repository, ISSUE_TYPE[kind]],
	);
	if (rows.rows.length < POOL_MINIMUM[kind]) {
		throw new Error(
			`${repository} has ${rows.rows.length} synced ${kind} rows; the seed needs at least ${POOL_MINIMUM[kind]}`,
		);
	}
	return rows.rows.map((row) => ({
		id: row.id,
		number: row.number,
		title: row.title,
		url: row.html_url,
		repository,
		kind,
	}));
}

async function removeSeed(client: Client, workspaceId: number): Promise<void> {
	const busy = await client.query(
		"SELECT 1 FROM agent_job WHERE workspace_id = $1 AND status IN ('RUNNING', 'QUEUED') LIMIT 1",
		[workspaceId],
	);
	if (busy.rowCount !== 0) {
		throw new Error(
			`${WORKSPACE_SLUG} has a review running or queued; run the seed once it is done`,
		);
	}
	const appended = await client.query<{ ids: number[] }>(
		`SELECT ARRAY(SELECT jsonb_array_elements_text(config_snapshot -> 'seedAppendedRevisionIds')::bigint)
		   AS ids
		 FROM agent_job WHERE id = $1`,
		[FIRST_JOB],
	);
	const pattern = `${ID_PREFIX}-%`;
	const jobs = "SELECT id FROM agent_job WHERE id::text LIKE $1";
	// Rows the server wrote while the demo was clicked through leave with the seed's jobs too: an
	// answer the reader gave, an approval. Responses, evidence bindings, placements, dispatches and
	// withdrawals cascade from feedback; an approval has no foreign key.
	const feedback = `SELECT id FROM feedback WHERE id::text LIKE $1 OR agent_job_id IN (${jobs})`;
	await client.query(`DELETE FROM feedback_approval WHERE feedback_id IN (${feedback})`, [pattern]);
	await client.query(`DELETE FROM feedback WHERE id IN (${feedback})`, [pattern]);
	await client.query(
		`DELETE FROM observation WHERE id::text LIKE $1 OR agent_job_id IN (${jobs})`,
		[pattern],
	);
	await client.query(`DELETE FROM agent_job WHERE id IN (${jobs})`, [pattern]);
	await rewindRevisions(client, workspaceId, appended.rows[0]?.ids ?? []);
	// The organization sync may already have dropped a synthetic membership, so the user goes once no
	// workspace holds them.
	const synthetic = `SELECT id FROM "user" WHERE login LIKE $1 AND native_id >= $2`;
	const syntheticParameters = [`${LOGIN_PREFIX}%`, NATIVE_ID_BASE];
	await client.query(
		`DELETE FROM workspace_membership WHERE workspace_id = $3 AND user_id IN (${synthetic})`,
		[...syntheticParameters, workspaceId],
	);
	await client.query(
		`DELETE FROM "user" WHERE id IN (${synthetic})
		 AND NOT EXISTS (SELECT 1 FROM workspace_membership wm WHERE wm.user_id = "user".id)`,
		syntheticParameters,
	);
}

async function insertDevelopers(client: Client, workspaceId: number): Promise<number[]> {
	const provider = await client.query<{ id: number }>(
		"SELECT id FROM identity_provider WHERE type = 'GITHUB' ORDER BY id LIMIT 1",
	);
	const providerId = provider.rows[0]?.id;
	if (providerId === undefined) {
		throw new Error("No GitHub identity provider in this database");
	}
	const ids: number[] = [];
	for (let index = 0; index < DEVELOPERS; index += 1) {
		const ordinal = String(index + 1).padStart(2, "0");
		const login = `${LOGIN_PREFIX}${ordinal}`;
		const user = await client.query<{ id: number }>(
			`INSERT INTO "user" (created_at, updated_at, login, name, type, native_id, provider_id, html_url)
			 VALUES (now(), now(), $1, $2, 'USER', $3, $4, $5)
			 ON CONFLICT (provider_id, native_id) DO UPDATE SET login = EXCLUDED.login
			 RETURNING id`,
			[
				login,
				`Synthetic developer ${ordinal}`,
				NATIVE_ID_BASE + index,
				providerId,
				`https://github.com/${login}`,
			],
		);
		const id = user.rows[0]?.id;
		if (id === undefined) {
			throw new Error(`Could not write ${login}`);
		}
		await client.query(
			`INSERT INTO workspace_membership (created_at, role, user_id, workspace_id, hidden)
			 VALUES (now(), 'MEMBER', $1, $2, false)
			 ON CONFLICT DO NOTHING`,
			[id, workspaceId],
		);
		ids.push(id);
	}
	return ids;
}

/**
 * Signs in at the running server as an existing dev administrator, or `seed-admin` when there is
 * none, and proves the server reads this database: the account it signed in must be here.
 */
async function devSignIn(
	client: Client,
	env: Record<string, string | undefined>,
): Promise<DevServer> {
	const admin = await client.query<{ username: string }>(
		`SELECT split_part(primary_email::text, '@', 1) AS username FROM account
		 WHERE primary_email::text LIKE '%@dev.invalid' AND app_role = 'APP_ADMIN' AND deleted_at IS NULL
		 ORDER BY id LIMIT 1`,
	);
	const username = admin.rows[0]?.username ?? "seed-admin";
	const server = `http://localhost:${positivePort(env.SERVER_PORT ?? "8080", "SERVER_PORT")}`;
	const login = await fetch(`${server}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ username, admin: true }),
		signal: AbortSignal.timeout(10_000),
	}).catch((error: unknown) => {
		throw new Error(`The server at ${server} must be running for the seed`, { cause: error });
	});
	const token = /(?:__Host-)?HEPHAESTUS_AT=(?<token>[^;]+)/u.exec(
		login.headers.get("set-cookie") ?? "",
	)?.groups?.token;
	if (!login.ok || token === undefined) {
		throw new Error(`Dev sign-in at ${server} failed with ${login.status}`);
	}
	const signedIn = await client.query(
		"SELECT 1 FROM account WHERE primary_email::text = $1 AND deleted_at IS NULL",
		[`${username}@dev.invalid`],
	);
	if (signedIn.rowCount !== 1) {
		throw new Error(`The server at ${server} does not read the database the seed would write`);
	}
	return { server, token };
}

/**
 * Completes the transparency notice for the seed's dev account, which the server requires before the
 * dev endpoints answer it. Removal writes only to the database, so it never needs this.
 */
async function completeNotice({ server, token }: DevServer): Promise<void> {
	await completeTransparencyNotice(async (method, route, body) => {
		const json =
			body === undefined
				? {}
				: { headers: { "content-type": "application/json" }, body: JSON.stringify(body) };
		const response = await fetch(`${server}${route}`, {
			method,
			...json,
			headers: { authorization: `Bearer ${token}`, ...json.headers },
			signal: AbortSignal.timeout(10_000),
		});
		if (!response.ok) {
			throw new Error(`${method} ${route} at ${server} failed with ${response.status}`);
		}
		return response.status === 204 ? undefined : parseJson(await response.text());
	});
}

/** POSTs to a dev endpoint; a refusal names the flag it needs, since that is the usual cause. */
async function postDev(
	{ server, token }: DevServer,
	route: string,
	query: URLSearchParams,
	what: string,
	body?: unknown,
): Promise<Response> {
	const response = await fetch(`${server}${route}?${query}`, {
		method: "POST",
		headers: {
			authorization: `Bearer ${token}`,
			...(body === undefined ? {} : { "content-type": "application/json" }),
		},
		body: body === undefined ? undefined : JSON.stringify(body),
		signal: AbortSignal.timeout(30_000),
	});
	if (!response.ok) {
		const detail = await response.text().catch(() => "");
		// The dev endpoints exist only while the flag is set, so a missing one is the flag.
		const hint =
			response.status === 404 ? " Set HEPHAESTUS_DEV_SEED_ENABLED=true in server/.env." : "";
		throw new Error(`The server refused ${what} with ${response.status}.${hint} ${detail}`.trim());
	}
	return response;
}

/**
 * Asks the running server for the revision a review would pin for each practice: it keeps one under
 * the current fingerprint scheme and appends one otherwise, and says which it appended.
 */
async function pinReviewRevisions(
	devServer: DevServer,
	workspaceId: number,
	practices: SeedPractice[],
): Promise<PinnedRevision[]> {
	const query = new URLSearchParams({ workspaceId: String(workspaceId) });
	for (const practice of practices) {
		query.append("slug", practice.slug);
	}
	const response = await postDev(
		devServer,
		"/api/dev/practice-revisions",
		query,
		"the practice revisions",
	);
	const body: unknown = await response.json();
	if (!Array.isArray(body) || !body.every(isPinnedRevision)) {
		throw new Error("The server answered the practice revisions in a shape the seed does not know");
	}
	return body;
}

async function readerOf(client: Client, workspaceId: number): Promise<number> {
	const rows = await client.query<{ id: number }>(
		`SELECT u.id FROM "user" u JOIN workspace_membership wm ON wm.user_id = u.id
		 WHERE u.login = $1 AND wm.workspace_id = $2`,
		[READER_LOGIN, workspaceId],
	);
	const id = rows.rows[0]?.id;
	if (id === undefined) {
		throw new Error(`${READER_LOGIN} is not a member of ${WORKSPACE_SLUG}`);
	}
	return id;
}

interface ObservationRow {
	jobId: string;
	practice: SeedPractice;
	artifact: Artifact;
	developerId: number;
	outcome: Outcome;
	/** Required exactly for NOT_MET (`Outcome.validate`). */
	severity?: Severity;
	summary: string;
	rationale?: string;
	citation: Citation;
	at: string;
}

/** Writes jobs and observations under the seed's ids. The first job keeps the appended revisions. */
class SeedWriter {
	jobs = 0;
	observations = 0;
	readonly #client: Client;
	readonly #workspaceId: number;
	readonly #appendedRevisionIds: number[];

	constructor(client: Client, workspaceId: number, appendedRevisionIds: number[]) {
		this.#client = client;
		this.#workspaceId = workspaceId;
		this.#appendedRevisionIds = appendedRevisionIds;
	}

	async job(artifact: Artifact, at: string): Promise<string> {
		this.jobs += 1;
		const jobId = seedId(TABLE.job, this.jobs);
		const isPullRequest = artifact.kind === "scm.pull_request";
		const metadata = isPullRequest
			? {
					title: artifact.title,
					pr_url: artifact.url,
					pr_number: artifact.number,
					pull_request_id: artifact.id,
					repository_full_name: artifact.repository,
				}
			: {
					title: artifact.title,
					issue_url: artifact.url,
					issue_number: artifact.number,
					issue_id: artifact.id,
					repository_full_name: artifact.repository,
				};
		const config =
			jobId === FIRST_JOB ? { seedAppendedRevisionIds: this.#appendedRevisionIds } : {};
		await this.#client.query(
			`INSERT INTO agent_job (
				id, workspace_id, job_type, status, metadata, output, config_snapshot, job_token, retry_count,
				created_at, started_at, completed_at, integration_kind, artifact_kind, available_at,
				delivery_attempts, purpose, evidence_snapshot, in_chat_prepared_at, in_app_prepared_at,
				practice_rollout_revision, practice_trigger_mode, trace_id
			) VALUES (
				$1, $2, $3, 'COMPLETED', $4, '{"outcome": "REVIEWED"}', $5, $6, 0,
				$7, $7, $8, 'GITHUB', $9, $7,
				0, 'PRACTICE_REVIEW', '{"manifest": {"contractVersion": "1.0.0"}}', $8, $8,
				0, 'AUTO', $10
			)`,
			[
				jobId,
				this.#workspaceId,
				isPullRequest ? "PULL_REQUEST_REVIEW" : "ISSUE_REVIEW",
				JSON.stringify(metadata),
				JSON.stringify(config),
				`seed-practices-across-${jobId}`,
				new Date(Date.parse(at) - 4 * 60_000).toISOString(),
				at,
				artifact.kind,
				jobId.replaceAll("-", "").slice(0, 32),
			],
		);
		return jobId;
	}

	async observation(row: ObservationRow): Promise<string> {
		this.observations += 1;
		const id = seedId(TABLE.observation, this.observations);
		const { citation, artifact } = row;
		const evidence = {
			citations: [
				{
					...citation,
					artifactPath:
						citation.sourceKind === "scm.pull-request.diff"
							? "inputs/context/diff.patch"
							: `${artifact.repository}#${artifact.number}`,
					quoteRedacted: false,
				},
			],
		};
		await this.#client.query(
			`INSERT INTO observation (
				id, occurrence_key, agent_job_id, practice_id, artifact_kind, artifact_id, about_user_id,
				summary, outcome, severity, evidence, evidence_rationale, observed_at,
				practice_revision_id, origin, workspace_id
			) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, 'LIVE', $15)`,
			[
				id,
				`seed-practices-across-${this.observations}`,
				row.jobId,
				row.practice.id,
				artifact.kind,
				artifact.id,
				row.developerId,
				row.summary,
				row.outcome,
				row.severity ?? null,
				JSON.stringify(evidence),
				row.rationale ?? null,
				row.at,
				row.practice.revisionId,
				this.#workspaceId,
			],
		);
		return id;
	}
}

const hour = (value: number): string => `${String(value).padStart(2, "0")}:00`;

/**
 * The synthetic developers' runs. Each practice group splits them by `SPLITS`. In a group of
 * several practices, the m-th developer with a standing leaves practice m modulo their count
 * unreviewed, the first `SKIPPERS_PER_PRACTICE` times round.
 */
async function seedDevelopers(
	writer: SeedWriter,
	pools: Record<Kind, Artifact[]>,
	developers: number[],
	practices: SeedPractice[],
): Promise<void> {
	const groupIndex = new Map(Object.keys(SPLITS).map((slug, index) => [slug, index]));
	const slugsOf = new Map(
		[...Map.groupBy(practices, (practice) => practice.groupSlug)].map(([group, members]) => [
			group,
			members.map((practice) => practice.slug).toSorted(),
		]),
	);
	const groupBucketFor = (groupSlug: string, index: number): Bucket => {
		const split = SPLITS[groupSlug];
		const group = groupIndex.get(groupSlug);
		return split === undefined || group === undefined ? "none" : bucketOf(split, group, index);
	};
	const bucketFor = (practice: SeedPractice, index: number): Bucket => {
		const bucket = groupBucketFor(practice.groupSlug, index);
		const slugs = slugsOf.get(practice.groupSlug) ?? [];
		if (bucket === "none" || slugs.length < 2) {
			return bucket;
		}
		let ordinal = 0;
		for (let earlier = 0; earlier < index; earlier += 1) {
			if (groupBucketFor(practice.groupSlug, earlier) !== "none") {
				ordinal += 1;
			}
		}
		const skips =
			ordinal < SKIPPERS_PER_PRACTICE * slugs.length &&
			slugs[ordinal % slugs.length] === practice.slug;
		return skips ? "none" : bucket;
	};

	for (const [index, developerId] of developers.entries()) {
		for (const kind of ["scm.pull_request", "scm.issue"] as const) {
			const isPullRequest = kind === "scm.pull_request";
			const pool = pools[kind];
			const runCount = isPullRequest ? 3 + (index % 5) : 3 + (index % 2);
			const observed = practices
				.filter((practice) => practice.kind === kind)
				.map((practice) => ({ practice, bucket: bucketFor(practice, index) }))
				.filter(({ bucket }) => bucket !== "none");
			for (let newest = 0; newest < runCount; newest += 1) {
				// Consecutive entries keep each run on its own work, since the pool holds at least runCount.
				const artifact = pool[(index * 5 + newest) % pool.length];
				if (artifact === undefined) {
					continue;
				}
				const at = isPullRequest
					? daysAgo(2 + newest * 11 + (index % 7), hour(9 + (index % 8)))
					: daysAgo(4 + newest * 13 + (index % 5), hour(10 + (index % 6)));
				const jobId = await writer.job(artifact, at);
				for (const { practice, bucket } of observed) {
					const problem = isProblem(bucket, newest);
					await writer.observation({
						jobId,
						practice,
						artifact,
						developerId,
						outcome: problem ? "NOT_MET" : "MET",
						severity: problem ? "MINOR" : undefined,
						summary: problem
							? "Synthetic observation: this work did not meet the practice."
							: "Synthetic observation: this work met the practice.",
						citation: {
							sourceKind: isPullRequest ? "scm.pull-request.core" : "scm.issue.core",
							path: isPullRequest ? "description" : "body",
							startLine: 1,
							endLine: 1,
							quote: artifact.title,
						},
						at,
					});
				}
			}
		}
	}
}

/** Writes every row of the demo in the caller's transaction; returns what the reader's feedback cites. */
async function seed(client: Client, workspaceId: number, appendedRevisionIds: number[]) {
	const practices = await seedPractices(client, workspaceId);
	const bySlug = new Map(practices.map((practice) => [practice.slug, practice]));
	const readerId = await readerOf(client, workspaceId);
	const pools = {
		"scm.pull_request": await artifactsOf(client, "scm.pull_request"),
		"scm.issue": await artifactsOf(client, "scm.issue"),
	};
	// Resolve all of the reader's work and practices before the first write.
	const readerRuns = READER_RUNS.map((run) => {
		const artifact = pools[run.artifact.kind].find((item) => item.number === run.artifact.number);
		if (artifact === undefined) {
			throw new Error(`${REPOSITORY[run.artifact.kind]}#${run.artifact.number} is not synced`);
		}
		const observations = run.observations.map((observation) => {
			const practice = bySlug.get(observation.practice);
			if (practice === undefined) {
				throw new Error(
					`Practice ${observation.practice} is not installed or not reviewed in ${WORKSPACE_SLUG}`,
				);
			}
			return { ...observation, practice };
		});
		return { ...run, artifact, observations };
	});

	const writer = new SeedWriter(client, workspaceId, appendedRevisionIds);
	await seedDevelopers(writer, pools, await insertDevelopers(client, workspaceId), practices);
	const jobIds = new Map<string, string>();
	const observationIds = new Map<string, string>();
	for (const run of readerRuns) {
		const jobId = await writer.job(run.artifact, run.at);
		jobIds.set(run.key, jobId);
		for (const observation of run.observations) {
			const id = await writer.observation({
				...observation,
				jobId,
				artifact: run.artifact,
				developerId: readerId,
				at: run.at,
			});
			observationIds.set(`${run.key}/${observation.practice.slug}`, id);
		}
	}
	return {
		jobs: writer.jobs,
		observations: writer.observations,
		cards: readerCards(readerId, jobIds, observationIds),
	};
}

async function main(): Promise<void> {
	const [mode = "seed", ...rest] = positionals;
	if ((mode !== "seed" && mode !== "remove") || rest.length > 0) {
		throw new Error(`Unknown mode ${positionals.join(" ")}; use "seed" (the default) or "remove"`);
	}
	const env = {
		...(await readEnvFile(path.join(import.meta.dirname, "..", "server", ".env"))),
		...process.env,
	};
	const host = env.POSTGRES_HOST ?? "localhost";
	// The seed writes straight into the database, so it refuses every host but this machine's.
	if (!isLoopbackHost(host)) {
		throw new Error("POSTGRES_HOST must be a loopback address");
	}
	const client = new Client({
		host,
		port: positivePort(env.POSTGRES_PORT ?? "5432", "POSTGRES_PORT"),
		database: env.POSTGRES_DB ?? "hephaestus",
		user: env.POSTGRES_USER ?? "root",
		password: env.POSTGRES_PASSWORD ?? "root",
	});
	await client.connect();
	try {
		const workspaceId = await workspaceToSeed(client, WORKSPACE_SLUG, mode);
		// Before any write: the server must answer dev sign-in from this very database.
		const devServer = await devSignIn(client, env);
		await client.query("BEGIN");
		await removeSeed(client, workspaceId);
		await client.query("COMMIT");
		if (mode === "remove") {
			console.log(
				`Removed the synthetic developers and the demo reviews and feedback of ${READER_LOGIN} from ${WORKSPACE_SLUG}.`,
			);
			return;
		}
		await completeNotice(devServer);
		// Outside a transaction: the server locks each practice row to append its revision.
		const pinned = await pinReviewRevisions(
			devServer,
			workspaceId,
			await seedPractices(client, workspaceId),
		);
		const appended = pinned.filter((revision) => revision.appended);
		console.log(
			`Practice revisions a review would pin: ${pinned.map((revision) => `${revision.slug}@${revision.revisionNumber}`).join(" ")}; ${appended.length} appended for the seed`,
		);
		const appendedIds = appended.map((revision) => revision.revisionId);
		const seeded = await writeOrRewind(client, workspaceId, appendedIds, async () =>
			seed(client, workspaceId, appendedIds),
		);
		// After the commit: the server reads the observations the feedback stands on.
		await postDev(
			devServer,
			"/api/dev/in-app-feedback",
			new URLSearchParams({ workspaceId: String(workspaceId) }),
			"the reader's feedback",
			seeded.cards,
		).catch((error: unknown) => {
			throw new Error(
				"The reviews are written but the reader's feedback is not; run the seed again",
				{ cause: error },
			);
		});
		console.log(
			`Seeded ${DEVELOPERS} synthetic developers and the demo of ${READER_LOGIN} in ${WORKSPACE_SLUG}: ${seeded.jobs} agent_job, ${seeded.observations} observation, ${seeded.cards.length} feedback`,
		);
	} catch (error) {
		await client.query("ROLLBACK").catch(() => undefined);
		throw error;
	} finally {
		await client.end();
	}
}

await main();
