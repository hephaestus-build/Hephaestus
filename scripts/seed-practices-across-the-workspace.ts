import path from "node:path";
import process from "node:process";
import { parseArgs } from "node:util";

import { Client } from "pg";

import { isLoopbackHost, positivePort, readEnvFile } from "./lib/env.ts";

/**
 * Seeds the development database with a workspace of synthetic developers, so Practices across the
 * workspace has a split to show: 40 members with clearly synthetic logins, each with completed
 * practice reviews of pull requests and issues already synced into the workspace and the
 * observations those reviews recorded. Every run is complete and no feedback is written, so no
 * sweeper, dispatcher or worker picks any of it up and nothing reaches a provider.
 *
 * An observation counts only against the revision its practice is reviewed under now, and only the
 * server fingerprints a revision, so the seed asks the running server for the revision a review would
 * pin, through dev sign-in, before it writes. The server says which revisions that request appended,
 * and the seed keeps their ids on its own first job, so removing the seed rewinds exactly those and
 * no revision a real review appended. Nothing is written before dev sign-in has answered and the
 * account it signed in is found in this database, which proves the server and the seed share it.
 *
 *     node scripts/seed-practices-across-the-workspace.ts          # remove the seed's rows, then insert them
 *     node scripts/seed-practices-across-the-workspace.ts remove   # remove the seed's rows only
 *
 * Flags, environment and defaults: docs/contributor/local-development.mdx § Seeding Practices
 * across the workspace.
 *
 * The reader, an existing member (`--reader`, default `ValentinGruener`), gets synthetic reviews too,
 * under the same revisions, so the page shows their own place on every split and their own figures
 * on the tiles. Their observations say they are synthetic and carry the seed's ids, so removing the
 * seed removes them and leaves every real observation about the reader in place.
 *
 * Each practice group gets its own split of the 40 developers over Needs attention, Mixed feedback,
 * Going well and no standing, chosen so the page shows every shape the privacy rule allows: most
 * groups and practices split, two groups collapse to has a standing against none yet, and two are
 * withheld. A part shows with six developers in it, the reader counted. In a group with several
 * practices, about six of its developers each leave one practice unreviewed, so the group less any
 * practice, and the practices less the group, count at least five whatever real reviews add. The
 * developers' runs reach back up to about 75 days, so the 30 day window reads fewer of them than the
 * term does.
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

const DEVELOPERS = 40;
/** Logins no provider hands out to a person, so a synthetic developer is never mistaken for one. */
const LOGIN_PREFIX = "synthetic-developer-";
/** Provider ids far above any real account's, so the seed never collides with a synced user. */
const NATIVE_ID_BASE = 990_000_000;
/** A UUID v4 prefix no real row carries; the fourth group says which table the row is in. */
const ID_PREFIX = "5eed0000-ac05-4000";
const TABLE = { job: "8000", observation: "8001" } as const;
/** The seed's first job, which keeps the ids of the revisions the server appended for the seed. */
const FIRST_JOB = `${ID_PREFIX}-${TABLE.job}-${(1).toString(16).padStart(12, "0")}`;
const EVIDENCE_CONTRACT_VERSION = "1.0.0";

type Bucket = "needs" | "mixed" | "well" | "none";

/**
 * How the 40 developers split in each group: Needs attention, Mixed feedback, Going well, none.
 * A group not listed here has no practice a pull request or issue review can observe, so nobody
 * gets a standing in it and the page withholds its split.
 */
const SPLITS: Record<string, [number, number, number, number]> = {
	"acting-on-review-feedback": [9, 9, 9, 13],
	"delivery-and-version-control-discipline": [9, 10, 9, 12],
	// Three at Needs attention: the split collapses to has a standing against none yet.
	"robust-error-handling": [3, 12, 12, 13],
	"secure-by-default-changes": [10, 9, 9, 12],
	"review-ready-work": [9, 9, 10, 12],
	"decisions-and-documentation": [9, 9, 9, 13],
	"constructive-code-review": [10, 10, 9, 11],
	// Two at Needs attention: collapses too.
	"testing-discipline": [2, 12, 13, 13],
	// Three with a standing: not even the collapsed split holds.
	"issue-traceability-and-lifecycle": [1, 1, 1, 37],
	"actionable-issue-authoring": [9, 9, 10, 12],
	"code-craftsmanship": [10, 9, 9, 12],
};

/** How many developers with a standing in a group leave each of its practices unreviewed. */
const SKIPPERS_PER_PRACTICE = 6;

/**
 * The reader's own bucket per group, in the order of `SPLITS`: every standing and a group with none,
 * so the You marker lands on each part of a split somewhere on the page.
 */
const READER_BUCKETS: readonly Bucket[] = [
	"mixed",
	"needs",
	"well",
	"needs",
	"well",
	"none",
	"mixed",
];

/** Which bucket developer `index` falls in for the group at `groupIndex`, shuffled per group. */
function bucketOf(
	split: [number, number, number, number],
	groupIndex: number,
	index: number,
): Bucket {
	const position = (index * 7 + groupIndex * 5) % DEVELOPERS;
	const [needs, mixed, well] = split;
	if (position < needs) {
		return "needs";
	}
	if (position < needs + mixed) {
		return "mixed";
	}
	return position < needs + mixed + well ? "well" : "none";
}

/**
 * Whether the run `newest` places from the newest is a problem for a developer in `bucket`. Under
 * the standing's recency weights, clean then one slip then clean reads Mixed feedback, and two slips
 * on the newest two pieces of work read Needs attention.
 */
function isProblem(bucket: Bucket, newest: number): boolean {
	if (bucket === "mixed") {
		return newest === 1;
	}
	if (bucket === "needs") {
		return newest !== 2;
	}
	return false;
}

/** The moment `days` before now at `hour` UTC, so a re-run stays inside the look-back. */
function daysAgo(days: number, hour: number): string {
	const day = new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);
	return new Date(`${day}T${String(hour).padStart(2, "0")}:00:00Z`).toISOString();
}

function seedId(table: string, ordinal: number): string {
	return `${ID_PREFIX}-${table}-${ordinal.toString(16).padStart(12, "0")}`;
}

interface Artifact {
	id: number;
	number: number;
	title: string;
	url: string;
	repository: string;
	kind: "scm.pull_request" | "scm.issue";
}

interface SeedPractice {
	id: number;
	slug: string;
	revisionId: number;
	groupSlug: string;
	kind: Artifact["kind"];
}

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

async function artifactsOf(
	client: Client,
	repository: string,
	kind: Artifact["kind"],
): Promise<Artifact[]> {
	const rows = await client.query<{ id: number; number: number; title: string; html_url: string }>(
		`SELECT i.id, i.number, i.title, i.html_url FROM issue i
		 JOIN repository r ON r.id = i.repository_id
		 WHERE r.name_with_owner = $1 AND i.issue_type = $2
		 ORDER BY i.number`,
		[repository, kind === "scm.pull_request" ? "PULL_REQUEST" : "ISSUE"],
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
	await client.query("DELETE FROM observation WHERE id::text LIKE $1", [pattern]);
	await client.query("DELETE FROM agent_job WHERE id::text LIKE $1", [pattern]);
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
		throw new Error(
			`The server refused the practice revisions with ${response.status}; is HEPHAESTUS_DEV_SEED_ENABLED set?`,
		);
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

async function seed(
	client: Client,
	workspaceId: number,
	appendedRevisionIds: number[],
): Promise<{ jobs: number; observations: number }> {
	const practices = await seedPractices(client, workspaceId);
	const groupIndex = new Map(Object.keys(SPLITS).map((slug, index) => [slug, index]));
	const pullRequests = await artifactsOf(client, PULL_REQUEST_REPOSITORY, "scm.pull_request");
	const issues = await artifactsOf(client, ISSUE_REPOSITORY, "scm.issue");
	const developers = await insertDevelopers(client, workspaceId);
	const reader = await readerOf(client, workspaceId);
	// The reader reviews last, so every synthetic developer keeps the runs and ordinals it had before.
	const readerIndex = developers.length;

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

	/** The bucket developer `index` falls in for one practice's group; the reader's is fixed. */
	const groupBucketFor = (groupSlug: string, index: number): Bucket => {
		const split = SPLITS[groupSlug];
		const group = groupIndex.get(groupSlug);
		if (split === undefined || group === undefined) {
			return "none";
		}
		return index === readerIndex
			? (READER_BUCKETS[group % READER_BUCKETS.length] ?? "none")
			: bucketOf(split, group, index);
	};

	/**
	 * The bucket developer `index` falls in for one practice: their group's, except in a group of
	 * several practices, where the m-th developer with a standing leaves practice m modulo their
	 * count unreviewed, the first `SKIPPERS_PER_PRACTICE` times round.
	 */
	const bucketFor = (practice: SeedPractice, index: number): Bucket => {
		const bucket = groupBucketFor(practice.groupSlug, index);
		const slugs = practicesOf.get(practice.groupSlug) ?? [];
		if (bucket === "none" || index === readerIndex || slugs.length < 2) {
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

	let jobs = 0;
	let observations = 0;
	for (const [index, developerId] of [...developers, reader].entries()) {
		for (const kind of ["scm.pull_request", "scm.issue"] as const) {
			const pool = kind === "scm.pull_request" ? pullRequests : issues;
			const runCount = kind === "scm.pull_request" ? 3 + (index % 5) : 3 + (index % 2);
			const observed = practices
				.filter((practice) => practice.kind === kind)
				.map((practice) => ({ practice, bucket: bucketFor(practice, index) }))
				.filter(({ bucket }) => bucket !== "none");
			for (let newest = 0; newest < runCount; newest += 1) {
				const artifact = pool[(index * 5 + newest * 3) % pool.length];
				if (artifact === undefined) {
					continue;
				}
				const at =
					kind === "scm.pull_request"
						? daysAgo(2 + newest * 11 + (index % 7), 9 + (index % 8))
						: daysAgo(4 + newest * 13 + (index % 5), 10 + (index % 6));
				jobs += 1;
				const jobId = seedId(TABLE.job, jobs);
				await insertJob(client, workspaceId, jobId, artifact, at);
				if (jobId === FIRST_JOB) {
					await client.query("UPDATE agent_job SET config_snapshot = $2 WHERE id = $1", [
						jobId,
						JSON.stringify({ seedAppendedRevisionIds: appendedRevisionIds }),
					]);
				}
				for (const { practice, bucket } of observed) {
					observations += 1;
					await insertObservation(client, {
						id: seedId(TABLE.observation, observations),
						ordinal: observations,
						workspaceId,
						jobId,
						practice,
						artifact,
						developerId,
						problem: isProblem(bucket, newest),
						at,
					});
				}
			}
		}
	}
	return { jobs, observations };
}

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

async function insertObservation(
	client: Client,
	row: {
		id: string;
		ordinal: number;
		workspaceId: number;
		jobId: string;
		practice: SeedPractice;
		artifact: Artifact;
		developerId: number;
		problem: boolean;
		at: string;
	},
): Promise<void> {
	const isPullRequest = row.artifact.kind === "scm.pull_request";
	const citation = {
		sourceKind: isPullRequest ? "scm.pull-request.core" : "scm.issue.core",
		artifactPath: `${row.artifact.repository}#${row.artifact.number}`,
		path: isPullRequest ? "description" : "body",
		startLine: 1,
		endLine: 1,
		quote: row.artifact.title,
		quoteRedacted: false,
	};
	await client.query(
		`INSERT INTO observation (
			id, occurrence_key, agent_job_id, practice_id, artifact_kind, artifact_id, about_user_id,
			summary, outcome, severity, evidence, evidence_rationale, observed_at,
			practice_revision_id, origin, workspace_id
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, NULL, $12, $13, 'LIVE', $14)`,
		[
			row.id,
			`seed-practices-across-${row.ordinal}`,
			row.jobId,
			row.practice.id,
			row.artifact.kind,
			row.artifact.id,
			row.developerId,
			row.problem
				? "Synthetic observation: this work did not meet the practice."
				: "Synthetic observation: this work met the practice.",
			// `Outcome.validate`: a severity exactly on NOT_MET.
			row.problem ? "NOT_MET" : "MET",
			row.problem ? "MINOR" : null,
			JSON.stringify({ citations: [citation] }),
			row.at,
			row.practice.revisionId,
			row.workspaceId,
		],
	);
}

async function main(): Promise<void> {
	const [mode = "seed", ...rest] = positionals;
	if ((mode !== "seed" && mode !== "remove") || rest.length > 0) {
		throw new Error(`Unknown mode ${positionals.join(" ")}; use "seed" (the default) or "remove"`);
	}
	const server = path.join(import.meta.dirname, "..", "server");
	const env = { ...(await readEnvFile(path.join(server, ".env"))), ...process.env };
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
				`Removed the synthetic developers and the synthetic reviews of ${READER_LOGIN} from ${WORKSPACE_SLUG}.`,
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
		const counts = await seed(
			client,
			workspaceId,
			appended.map((revision) => revision.revisionId),
		);
		await client.query("COMMIT");
		console.log(
			`Seeded ${DEVELOPERS} synthetic developers and synthetic reviews of ${READER_LOGIN} in ${WORKSPACE_SLUG}: ${counts.jobs} agent_job, ${counts.observations} observation`,
		);
	} catch (error) {
		await client.query("ROLLBACK").catch(() => undefined);
		throw error;
	} finally {
		await client.end();
	}
}

await main();
