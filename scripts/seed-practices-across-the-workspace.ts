import path from "node:path";
import process from "node:process";
import { parseArgs } from "node:util";

import { Client } from "pg";

import { isLoopbackHost, positivePort, readEnvFile } from "./lib/env.ts";
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

/**
 * Seeds the development database with a demo of Practices across the workspace and the Practice
 * profile: 40 synthetic developers with clearly synthetic logins, each with completed practice
 * reviews of pull requests and issues already synced into the workspace and the observations those
 * reviews recorded, and for the reader, an existing member, a written history of reviews and the
 * in-app feedback composed from it. What the demo holds is in `scripts/lib/practices-demo.ts`.
 * Every run is complete and the only feedback is the reader's in-app feedback, so no sweeper,
 * dispatcher or worker picks any of it up and nothing reaches a provider.
 *
 * An observation counts only against the revision its practice is reviewed under now, and only the
 * server fingerprints a revision, so the seed asks the running server for the revision a review would
 * pin, through dev sign-in, before it writes. The server says which revisions that request appended,
 * and the seed keeps their ids on its own first job, so removing the seed rewinds exactly those and
 * no revision a real review appended. Nothing is written before dev sign-in has answered and the
 * account it signed in is found in this database, which proves the server and the seed share it.
 * The reader's feedback goes through the server too, so its rows are the ones the application writes.
 *
 *     node scripts/seed-practices-across-the-workspace.ts          # remove the seed's rows, then insert them
 *     node scripts/seed-practices-across-the-workspace.ts remove   # remove the seed's rows only
 *
 * Flags, environment and defaults: docs/contributor/local-development.mdx § Seeding the practices
 * demo.
 */

const { values: flags, positionals } = parseArgs({
	options: {
		workspace: { type: "string" },
		"pull-request-repository": { type: "string" },
		"issue-repository": { type: "string" },
		reader: { type: "string" },
	},
	allowPositionals: true,
});
const setting = (flag: keyof typeof flags, name: string, fallback: string): string =>
	flags[flag] ?? process.env[name] ?? fallback;

const WORKSPACE_SLUG = setting("workspace", "SEED_WORKSPACE_SLUG", "hephaestustest");
const PULL_REQUEST_REPOSITORY = setting(
	"pull-request-repository",
	"SEED_PULL_REQUEST_REPOSITORY",
	"HephaestusTest/practice-validation",
);
const ISSUE_REPOSITORY = setting(
	"issue-repository",
	"SEED_ISSUE_REPOSITORY",
	"HephaestusTest/MaxTestRepo",
);
const READER_LOGIN = setting("reader", "SEED_READER_LOGIN", "ValentinGruener");

const EVIDENCE_CONTRACT_VERSION = "1.0.0";

interface Artifact {
	id: number;
	number: number;
	title: string;
	url: string;
	repository: string;
	kind: ArtifactRef["kind"];
}

interface SeedPractice {
	id: number;
	slug: string;
	revisionId: number;
	groupSlug: string;
	kind: Artifact["kind"];
}

const repositoryOf = (kind: ArtifactRef["kind"]): string =>
	kind === "scm.pull_request" ? PULL_REQUEST_REPOSITORY : ISSUE_REPOSITORY;
const issueTypeOf = (kind: ArtifactRef["kind"]): string =>
	kind === "scm.pull_request" ? "PULL_REQUEST" : "ISSUE";

/** The practices a pull request or issue review observes in a group the seed splits. */
async function seedPractices(client: Client, workspaceId: number): Promise<SeedPractice[]> {
	const rows = await client.query<{
		id: number;
		slug: string;
		revision_id: number | null;
		group_slug: string;
		applies_to: string;
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
						kind: row.applies_to === "scm.issue" ? "scm.issue" : "scm.pull_request",
					},
				],
	);
}

/** Every synced pull request or issue of one kind, the pool the synthetic developers' runs draw from. */
async function artifactsOf(client: Client, kind: Artifact["kind"]): Promise<Artifact[]> {
	const repository = repositoryOf(kind);
	const rows = await client.query<{ id: number; number: number; title: string; html_url: string }>(
		`SELECT i.id, i.number, i.title, i.html_url FROM issue i
		 JOIN repository r ON r.id = i.repository_id
		 WHERE r.name_with_owner = $1 AND i.issue_type = $2
		 ORDER BY i.number`,
		[repository, issueTypeOf(kind)],
	);
	// A developer's runs step three apart through the pool, up to seven pull requests and four issues;
	// a pool at least that long whose length three does not divide keeps every run on its own work.
	const needed = kind === "scm.pull_request" ? 7 : 4;
	if (rows.rows.length < needed || rows.rows.length % 3 === 0) {
		throw new Error(
			`${repository} has ${rows.rows.length} synced ${kind} rows, which cannot keep each run on its own work`,
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
	const busy = await client.query<{ count: string }>(
		"SELECT count(*) AS count FROM agent_job WHERE workspace_id = $1 AND status IN ('RUNNING', 'QUEUED')",
		[workspaceId],
	);
	if (Number(busy.rows[0]?.count ?? 0) > 0) {
		throw new Error(
			`${WORKSPACE_SLUG} has a review running or queued; run the seed once it is done`,
		);
	}
	const appended = await client.query<{ ids: number[] | null }>(
		`SELECT ARRAY(SELECT jsonb_array_elements_text(config_snapshot -> 'seedAppendedRevisionIds')::bigint)
		   AS ids
		 FROM agent_job WHERE id = $1`,
		[FIRST_JOB],
	);
	const pattern = `${ID_PREFIX}-%`;
	const jobs = "SELECT id FROM agent_job WHERE id::text LIKE $1";
	// Whatever hangs off the seed's jobs leaves with them, including rows the server wrote while the
	// demo was clicked through: an answer the reader gave, an approval. Responses, evidence bindings,
	// placements, dispatches and withdrawals cascade from feedback; an approval has no foreign key.
	const feedback = `SELECT id FROM feedback WHERE id::text LIKE $1 OR agent_job_id IN (${jobs})`;
	await client.query(`DELETE FROM feedback_approval WHERE feedback_id IN (${feedback})`, [pattern]);
	await client.query(`DELETE FROM feedback WHERE id IN (${feedback})`, [pattern]);
	await client.query(
		`DELETE FROM observation WHERE id::text LIKE $1 OR agent_job_id IN (${jobs})`,
		[pattern],
	);
	await client.query(`DELETE FROM agent_job WHERE id IN (${jobs})`, [pattern]);
	// Only a revision the server appended for the seed, still current, still differing from the one
	// before it only in its fingerprint, and pinned by no observation goes back to the one before it,
	// which is the revision the next review would replace with this same one.
	const rewound = await client.query<{ id: number }>(
		`WITH appended AS (
			SELECT p.id AS practice_id, cur.id AS current_id, prev.id AS previous_id
			FROM practice p
			JOIN practice_revision cur ON cur.id = p.current_revision_id
			JOIN practice_revision prev ON prev.practice_id = p.id
			 AND prev.revision_number = cur.revision_number - 1
			WHERE p.workspace_id = $1
			  AND cur.id = ANY($2::bigint[])
			  AND to_jsonb(cur) - $3::text[] = to_jsonb(prev) - $3::text[]
			  AND NOT EXISTS (SELECT 1 FROM observation o WHERE o.practice_revision_id = cur.id)
		)
		UPDATE practice p SET current_revision_id = appended.previous_id
		FROM appended WHERE p.id = appended.practice_id
		RETURNING appended.current_id AS id`,
		[
			workspaceId,
			appended.rows[0]?.ids ?? [],
			["id", "revision_number", "review_rule_fingerprint", "created_at"],
		],
	);
	await client.query("DELETE FROM practice_revision WHERE id = ANY($1::bigint[])", [
		rewound.rows.map((row) => row.id),
	]);
	// A synthetic developer leaves with their membership. The organization sync may already have
	// dropped the membership, so the user goes whenever no workspace holds them any more.
	const synthetic = `SELECT id FROM "user" WHERE login LIKE '${LOGIN_PREFIX}%' AND native_id >= ${NATIVE_ID_BASE}`;
	await client.query(
		`DELETE FROM workspace_membership WHERE workspace_id = $1 AND user_id IN (${synthetic})`,
		[workspaceId],
	);
	await client.query(
		`DELETE FROM "user" WHERE id IN (${synthetic})
		 AND NOT EXISTS (SELECT 1 FROM workspace_membership wm WHERE wm.user_id = "user".id)`,
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
 * none, and proves the server reads this database: the account it signed in must be here. Nothing is
 * written before this answers, so a database that is not a dev server's is never touched.
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

function isPinnedRevision(value: unknown): value is PinnedRevision {
	return (
		typeof value === "object" &&
		value !== null &&
		"slug" in value &&
		typeof value.slug === "string" &&
		"revisionId" in value &&
		typeof value.revisionId === "number" &&
		"revisionNumber" in value &&
		typeof value.revisionNumber === "number" &&
		"appended" in value &&
		typeof value.appended === "boolean"
	);
}

/** A refusal from a dev endpoint says the flag it needs, since that is the usual cause. */
async function refusal(response: Response, what: string): Promise<Error> {
	const detail = await response.text().catch(() => "");
	return new Error(
		`The server refused ${what} with ${response.status}; is HEPHAESTUS_DEV_SEED_ENABLED set? ${detail}`.trim(),
	);
}

/**
 * Asks the running server for the revision a review would pin for each practice: it keeps one under
 * the current fingerprint scheme and appends one otherwise, and says which it appended.
 */
async function pinReviewRevisions(
	{ server, token }: DevServer,
	workspaceId: number,
	practices: SeedPractice[],
): Promise<PinnedRevision[]> {
	const query = new URLSearchParams({ workspaceId: String(workspaceId) });
	for (const practice of practices) {
		query.append("slug", practice.slug);
	}
	const response = await fetch(`${server}/api/dev/practice-revisions?${query}`, {
		method: "POST",
		headers: { authorization: `Bearer ${token}` },
		signal: AbortSignal.timeout(30_000),
	});
	if (!response.ok) {
		throw await refusal(response, "the practice revisions");
	}
	const body: unknown = await response.json();
	if (!Array.isArray(body) || !body.every(isPinnedRevision)) {
		throw new Error("The server answered the practice revisions in a shape the seed does not know");
	}
	return body;
}

/** The member whose page the seed is for; they must already belong to the workspace. */
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

/** The pull request or issue a reader's run names, looked up by number; never invented. */
async function artifactOf(client: Client, ref: ArtifactRef): Promise<Artifact> {
	const repository = repositoryOf(ref.kind);
	const rows = await client.query<{ id: number; title: string; html_url: string }>(
		`SELECT i.id, i.title, i.html_url FROM issue i
		 JOIN repository r ON r.id = i.repository_id
		 WHERE r.name_with_owner = $1 AND i.number = $2 AND i.issue_type = $3`,
		[repository, ref.number, issueTypeOf(ref.kind)],
	);
	const row = rows.rows[0];
	if (!row) {
		throw new Error(`${repository}#${ref.number} is not synced into this database`);
	}
	return {
		id: row.id,
		number: ref.number,
		title: row.title,
		url: row.html_url,
		repository,
		kind: ref.kind,
	};
}

/** Counts of the rows written, for the summary line. */
interface Counts {
	jobs: number;
	observations: number;
}

/** What the reader's rows need to know to carry the reader's feedback. */
interface ReaderRows {
	readerId: number;
	jobIds: Map<string, string>;
	observationIds: Map<string, string>;
}

/** Writes jobs and observations under the seed's ids, counting them; the first job keeps the appended revisions. */
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
		await insertJob(this.#client, this.#workspaceId, jobId, artifact, at);
		if (jobId === FIRST_JOB) {
			await this.#client.query("UPDATE agent_job SET config_snapshot = $2 WHERE id = $1", [
				jobId,
				JSON.stringify({ seedAppendedRevisionIds: this.#appendedRevisionIds }),
			]);
		}
		return jobId;
	}

	async observation(row: Omit<ObservationRow, "id" | "ordinal" | "workspaceId">): Promise<string> {
		this.observations += 1;
		const id = seedId(TABLE.observation, this.observations);
		await insertObservation(this.#client, {
			...row,
			id,
			ordinal: this.observations,
			workspaceId: this.#workspaceId,
		});
		return id;
	}
}

/**
 * The synthetic developers' runs: each practice group splits them by `SPLITS`, and in a group of
 * several practices the m-th developer with a standing leaves practice m modulo their count
 * unreviewed, the first `SKIPPERS_PER_PRACTICE` times round.
 */
async function seedDevelopers(
	client: Client,
	writer: SeedWriter,
	developers: number[],
	practices: SeedPractice[],
): Promise<void> {
	const pullRequests = await artifactsOf(client, "scm.pull_request");
	const issues = await artifactsOf(client, "scm.issue");
	const groupIndex = new Map(Object.keys(SPLITS).map((slug, index) => [slug, index]));
	/** Each group's seeded practices, in one order, so a developer's skipped practice is stable. */
	const practicesOf = new Map<string, string[]>();
	for (const practice of practices) {
		practicesOf.set(practice.groupSlug, [
			...(practicesOf.get(practice.groupSlug) ?? []),
			practice.slug,
		]);
	}
	for (const slugs of practicesOf.values()) {
		slugs.sort();
	}
	const groupBucketFor = (groupSlug: string, index: number): Bucket => {
		const split = SPLITS[groupSlug];
		const group = groupIndex.get(groupSlug);
		return split === undefined || group === undefined ? "none" : bucketOf(split, group, index);
	};
	const bucketFor = (practice: SeedPractice, index: number): Bucket => {
		const bucket = groupBucketFor(practice.groupSlug, index);
		const slugs = practicesOf.get(practice.groupSlug) ?? [];
		if (bucket === "none" || slugs.length < 2) {
			return bucket;
		}
		const ordinal = Array.from({ length: index }, (_, earlier) => earlier).filter(
			(earlier) => groupBucketFor(practice.groupSlug, earlier) !== "none",
		).length;
		const skips =
			ordinal < SKIPPERS_PER_PRACTICE * slugs.length &&
			slugs[ordinal % slugs.length] === practice.slug;
		return skips ? "none" : bucket;
	};

	for (const [index, developerId] of developers.entries()) {
		for (const kind of ["scm.pull_request", "scm.issue"] as const) {
			const isPullRequest = kind === "scm.pull_request";
			const pool = isPullRequest ? pullRequests : issues;
			const runCount = isPullRequest ? 3 + (index % 5) : 3 + (index % 2);
			const observed = practices
				.filter((practice) => practice.kind === kind)
				.map((practice) => ({ practice, bucket: bucketFor(practice, index) }))
				.filter(({ bucket }) => bucket !== "none");
			for (let newest = 0; newest < runCount; newest += 1) {
				const artifact = pool[(index * 5 + newest * 3) % pool.length];
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

/** The reader's runs, written after every check passed; returns the ids their feedback cites. */
async function seedReader(
	writer: SeedWriter,
	readerId: number,
	artifacts: Map<string, Artifact>,
	bySlug: Map<string, SeedPractice>,
): Promise<ReaderRows> {
	const jobIds = new Map<string, string>();
	const observationIds = new Map<string, string>();
	for (const run of READER_RUNS) {
		const artifact = artifacts.get(run.key);
		if (artifact === undefined) {
			throw new Error(`Unresolved work for run ${run.key}`);
		}
		const jobId = await writer.job(artifact, run.at);
		jobIds.set(run.key, jobId);
		for (const observation of run.observations) {
			const practice = bySlug.get(observation.practice);
			if (practice === undefined) {
				throw new Error(`Unresolved practice ${observation.practice}`);
			}
			const id = await writer.observation({
				jobId,
				practice,
				artifact,
				developerId: readerId,
				outcome: observation.outcome,
				severity: observation.severity,
				summary: observation.summary,
				rationale: observation.rationale,
				citation: observation.citation,
				at: run.at,
			});
			observationIds.set(`${run.key}/${observation.practice}`, id);
		}
	}
	return { readerId, jobIds, observationIds };
}

async function seed(
	client: Client,
	workspaceId: number,
	appendedRevisionIds: number[],
): Promise<Counts & ReaderRows> {
	const practices = await seedPractices(client, workspaceId);
	const bySlug = new Map(practices.map((practice) => [practice.slug, practice]));
	const readerId = await readerOf(client, workspaceId);
	const readerArtifacts = new Map<string, Artifact>();
	for (const run of READER_RUNS) {
		readerArtifacts.set(run.key, await artifactOf(client, run.artifact));
		const missing = run.observations.find((observation) => !bySlug.has(observation.practice));
		if (missing !== undefined) {
			throw new Error(
				`Practice ${missing.practice} is not installed or not reviewed in ${WORKSPACE_SLUG}`,
			);
		}
	}
	const writer = new SeedWriter(client, workspaceId, appendedRevisionIds);
	await seedDevelopers(client, writer, await insertDevelopers(client, workspaceId), practices);
	const reader = await seedReader(writer, readerId, readerArtifacts, bySlug);
	return { jobs: writer.jobs, observations: writer.observations, ...reader };
}

const hour = (value: number): string => `${String(value).padStart(2, "0")}:00`;

async function insertJob(
	client: Client,
	workspaceId: number,
	jobId: string,
	artifact: Artifact,
	at: string,
): Promise<void> {
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
	const startedAt = new Date(new Date(at).getTime() - 4 * 60_000).toISOString();
	await client.query(
		`INSERT INTO agent_job (
			id, workspace_id, job_type, status, metadata, output, config_snapshot, job_token, retry_count,
			created_at, started_at, completed_at, integration_kind, artifact_kind, available_at,
			delivery_attempts, purpose, evidence_snapshot, in_chat_prepared_at, in_app_prepared_at,
			practice_rollout_revision, practice_trigger_mode, trace_id
		) VALUES (
			$1, $2, $3, 'COMPLETED', $4, '{"outcome": "REVIEWED"}', '{}', $5, 0,
			$6, $6, $7, 'GITHUB', $8, $6,
			0, 'PRACTICE_REVIEW', $9, $7, $7,
			0, 'AUTO', $10
		)`,
		[
			jobId,
			workspaceId,
			isPullRequest ? "PULL_REQUEST_REVIEW" : "ISSUE_REVIEW",
			JSON.stringify(metadata),
			`seed-practices-across-${jobId}`,
			startedAt,
			at,
			artifact.kind,
			JSON.stringify({ manifest: { contractVersion: EVIDENCE_CONTRACT_VERSION } }),
			jobId.replaceAll("-", "").slice(0, 32),
		],
	);
}

interface ObservationRow {
	id: string;
	ordinal: number;
	workspaceId: number;
	jobId: string;
	practice: SeedPractice;
	artifact: Artifact;
	developerId: number;
	outcome: Outcome;
	/** Required exactly for NOT_MET (`Outcome.validate`). */
	severity: Severity | undefined;
	summary: string;
	rationale?: string;
	citation: Citation;
	at: string;
}

async function insertObservation(client: Client, row: ObservationRow): Promise<void> {
	const citation = {
		sourceKind: row.citation.sourceKind,
		artifactPath:
			row.citation.sourceKind === "scm.pull-request.diff"
				? "inputs/context/diff.patch"
				: `${row.artifact.repository}#${row.artifact.number}`,
		path: row.citation.path,
		...(row.citation.side ? { side: row.citation.side } : {}),
		startLine: row.citation.startLine,
		endLine: row.citation.endLine,
		quote: row.citation.quote,
		quoteRedacted: false,
	};
	await client.query(
		`INSERT INTO observation (
			id, occurrence_key, agent_job_id, practice_id, artifact_kind, artifact_id, about_user_id,
			summary, outcome, severity, evidence, evidence_rationale, observed_at,
			practice_revision_id, origin, workspace_id
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, 'LIVE', $15)`,
		[
			row.id,
			`seed-practices-across-${row.ordinal}`,
			row.jobId,
			row.practice.id,
			row.artifact.kind,
			row.artifact.id,
			row.developerId,
			row.summary,
			row.outcome,
			row.severity ?? null,
			JSON.stringify({ citations: [citation] }),
			row.rationale ?? null,
			row.at,
			row.practice.revisionId,
			row.workspaceId,
		],
	);
}

/** Writes the reader's in-app feedback through the server, which writes it as a review would. */
async function writeReaderFeedback(
	{ server, token }: DevServer,
	workspaceId: number,
	rows: ReaderRows,
): Promise<number> {
	const cards = readerCards(rows.readerId, rows.jobIds, rows.observationIds);
	const response = await fetch(
		`${server}/api/dev/in-app-feedback?${new URLSearchParams({ workspaceId: String(workspaceId) })}`,
		{
			method: "POST",
			headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
			body: JSON.stringify(cards),
			signal: AbortSignal.timeout(30_000),
		},
	);
	if (!response.ok) {
		throw await refusal(response, "the reader's feedback");
	}
	return cards.length;
}

async function main(): Promise<void> {
	const [mode = "seed", ...rest] = positionals;
	if ((mode !== "seed" && mode !== "remove") || rest.length > 0) {
		throw new Error(`Unknown mode ${positionals.join(" ")}; use "seed" (the default) or "remove"`);
	}
	const serverDirectory = path.join(import.meta.dirname, "..", "server");
	const env = { ...(await readEnvFile(path.join(serverDirectory, ".env"))), ...process.env };
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
		const workspace = await client.query<{ id: number }>(
			"SELECT id FROM workspace WHERE slug = $1",
			[WORKSPACE_SLUG],
		);
		const workspaceId = workspace.rows[0]?.id;
		if (workspaceId === undefined) {
			throw new Error(`No workspace with slug ${WORKSPACE_SLUG}`);
		}
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
		await client.query("BEGIN");
		const seeded = await seed(
			client,
			workspaceId,
			appended.map((revision) => revision.revisionId),
		);
		await client.query("COMMIT");
		// After the commit: the server reads the observations the feedback stands on.
		const cards = await writeReaderFeedback(devServer, workspaceId, seeded).catch(
			(error: unknown) => {
				throw new Error(
					"The reviews are written but the reader's feedback is not; run the seed again",
					{ cause: error },
				);
			},
		);
		console.log(
			`Seeded ${DEVELOPERS} synthetic developers and the demo of ${READER_LOGIN} in ${WORKSPACE_SLUG}: ${seeded.jobs} agent_job, ${seeded.observations} observation, ${cards} feedback`,
		);
	} catch (error) {
		await client.query("ROLLBACK").catch(() => undefined);
		throw error;
	} finally {
		await client.end();
	}
}

await main();
