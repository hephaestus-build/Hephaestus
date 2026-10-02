import path from "node:path";
import process from "node:process";
import { parseArgs } from "node:util";

import { Client } from "pg";

import { isLoopbackHost, positivePort, readEnvFile } from "./lib/env.ts";

/**
 * Seeds the development database with a workspace of synthetic developers, so Practices across the
 * workspace has a split to show: 24 members with clearly synthetic logins, each with completed
 * practice reviews of pull requests and issues already synced into the workspace and the
 * observations those reviews recorded. Every run is complete and no feedback is written, so no
 * sweeper, dispatcher or worker picks any of it up and nothing reaches a provider.
 *
 *     node scripts/seed-practices-across-the-workspace.ts          # remove the seed's rows, then insert them
 *     node scripts/seed-practices-across-the-workspace.ts remove   # remove the seed's rows only
 *
 * Flags, environment and defaults: docs/contributor/local-development.mdx § Seeding Practices
 * across the workspace.
 *
 * Each practice group gets its own split of the 24 developers over Needs attention, Mixed feedback,
 * Going well and no standing, chosen so the page shows every shape the privacy rule allows: most
 * groups split, one collapses to has a standing against none yet, and one is withheld. The
 * developers' runs reach back up to about 75 days, so the 30 day window reads fewer of them than the
 * term does.
 */

const { values: flags, positionals } = parseArgs({
	options: {
		workspace: { type: "string" },
		"pull-request-repository": { type: "string" },
		"issue-repository": { type: "string" },
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

const DEVELOPERS = 24;
/** Logins no provider hands out to a person, so a synthetic developer is never mistaken for one. */
const LOGIN_PREFIX = "synthetic-developer-";
/** Provider ids far above any real account's, so the seed never collides with a synced user. */
const NATIVE_ID_BASE = 990_000_000;
/** A UUID v4 prefix no real row carries; the fourth group says which table the row is in. */
const ID_PREFIX = "5eed0000-ac05-4000";
const TABLE = { job: "8000", observation: "8001" } as const;
const EVIDENCE_CONTRACT_VERSION = "1.0.0";

type Bucket = "needs" | "mixed" | "well" | "none";

/**
 * How the 24 developers split in each group: Needs attention, Mixed feedback, Going well, none.
 * A group not listed here has no practice a pull request or issue review can observe, so nobody
 * gets a standing in it and the page withholds its split.
 */
const SPLITS: Record<string, [number, number, number, number]> = {
	"acting-on-review-feedback": [6, 8, 7, 3],
	"delivery-and-version-control-discipline": [5, 9, 8, 2],
	// Two at Needs attention: the split collapses to has a standing against none yet.
	"robust-error-handling": [2, 9, 8, 5],
	"secure-by-default-changes": [7, 7, 5, 5],
	"review-ready-work": [7, 6, 9, 2],
	"decisions-and-documentation": [5, 6, 6, 7],
	"constructive-code-review": [6, 8, 7, 3],
	"testing-discipline": [5, 6, 9, 4],
	// Three at Needs attention and four without a standing: not even the collapsed split holds.
	"issue-traceability-and-lifecycle": [3, 8, 9, 4],
	"actionable-issue-authoring": [5, 7, 6, 6],
	"code-craftsmanship": [6, 7, 8, 3],
};

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
	revisionId: number;
	groupSlug: string;
	kind: Artifact["kind"];
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
	const pattern = `${ID_PREFIX}-%`;
	await client.query("DELETE FROM observation WHERE id::text LIKE $1", [pattern]);
	await client.query("DELETE FROM agent_job WHERE id::text LIKE $1", [pattern]);
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

async function seed(
	client: Client,
	workspaceId: number,
): Promise<{ jobs: number; observations: number }> {
	const practiceRows = await client.query<{
		id: number;
		revision_id: number | null;
		group_slug: string;
		applies_to: string;
	}>(
		`SELECT p.id, p.current_revision_id AS revision_id, g.slug AS group_slug, p.applies_to
		 FROM practice p JOIN practice_group g ON g.id = p.practice_group_id
		 WHERE p.workspace_id = $1
		   AND p.applies_to IN ('scm.pull_request', 'scm.issue')
		   AND (p.autonomy IS NULL OR p.autonomy <> 'OFF')`,
		[workspaceId],
	);
	const practices: SeedPractice[] = practiceRows.rows.flatMap((row) =>
		row.revision_id === null || !(row.group_slug in SPLITS)
			? []
			: [
					{
						id: row.id,
						revisionId: row.revision_id,
						groupSlug: row.group_slug,
						kind: row.applies_to === "scm.issue" ? "scm.issue" : "scm.pull_request",
					},
				],
	);
	const groupIndex = new Map(Object.keys(SPLITS).map((slug, index) => [slug, index]));
	const pullRequests = await artifactsOf(client, PULL_REQUEST_REPOSITORY, "scm.pull_request");
	const issues = await artifactsOf(client, ISSUE_REPOSITORY, "scm.issue");
	const developers = await insertDevelopers(client, workspaceId);

	/** The bucket developer `index` falls in for one practice's group. */
	const bucketFor = (practice: SeedPractice, index: number): Bucket => {
		const split = SPLITS[practice.groupSlug];
		const group = groupIndex.get(practice.groupSlug);
		return split === undefined || group === undefined ? "none" : bucketOf(split, group, index);
	};

	let jobs = 0;
	let observations = 0;
	for (const [index, developerId] of developers.entries()) {
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
		await client.query("BEGIN");
		await removeSeed(client, workspaceId);
		if (mode === "remove") {
			await client.query("COMMIT");
			console.log(`Removed the synthetic developers from ${WORKSPACE_SLUG}.`);
			return;
		}
		const counts = await seed(client, workspaceId);
		await client.query("COMMIT");
		console.log(
			`Seeded ${DEVELOPERS} synthetic developers in ${WORKSPACE_SLUG}: ${counts.jobs} agent_job, ${counts.observations} observation`,
		);
	} catch (error) {
		await client.query("ROLLBACK").catch(() => undefined);
		throw error;
	} finally {
		await client.end();
	}
}

await main();
