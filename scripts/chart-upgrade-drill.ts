/**
 * A self-hoster's release change on a cluster: install the latest published release with its own chart, images and
 * scripts, then upgrade to the candidate with the candidate's chart. A release whose migration guide says
 * "Stop every role" takes the three phases of docs/admin/upgrade.mdx § Stopped schema upgrades; any other takes one
 * rolling upgrade. Both end with the candidate's readiness and authenticated-read checks. Entry-specific steps of a
 * migration note, such as the conversation-sandbox cutover of an older release, are not part of the drill.
 *
 * After a rolling upgrade, the drill returns every role to the previous release on the migrated schema, as an operator
 * does when the new release fails after its migration (docs/admin/runtime-roles.mdx § Rollouts and rollback). It
 * queues a review and fails unless the previous release claims it. Then it rolls forward to the candidate.
 */
import { randomUUID } from "node:crypto";
import { cp, mkdir, mkdtemp, readFile, readdir, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { parse } from "yaml";

import { NAMESPACE, psql, RELEASE, revision, SANDBOXES } from "./kubernetes-cluster.ts";
import { changedPostgresql } from "./lib/chart-postgresql.ts";
import { asRecord, isRecord, parseJson } from "./lib/json.ts";
import { stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { latestReleaseOf } from "./lib/previous-release.ts";
import { output, run } from "./lib/process.ts";

const ROOT = path.resolve(import.meta.dirname, "..");
/** The review that `practice-native/result-parent.sql` seeds, with its practice and item. */
const REVIEW = "00000000-0000-0000-0000-000000978011";

const { values } = parseArgs({
	options: {
		work: { type: "string" },
		images: { type: "string" },
		commit: { type: "string" },
		profile: { type: "string", default: "dev" },
	},
});
const { commit, profile } = values;
if (values.work === undefined || values.images === undefined || commit === undefined) {
	throw new Error(
		"Usage: node scripts/chart-upgrade-drill.ts --work <dir> --images <candidate images file> --commit <sha> [--profile dev]",
	);
}

// The cluster script runs in two checkouts, so both paths are absolute.
const work = path.resolve(values.work);
const images = path.resolve(values.images);
await mkdir(work, { recursive: true, mode: 0o700 });

const previous = latestReleaseOf(commit);
if (previous === undefined) {
	throw new Error(`No published release is merged into ${commit}`);
}
const resolved = await output("git", ["rev-parse", `${previous}^{commit}`], { cwd: ROOT });
const previousCommit = resolved.trim();
const fragmentFiles = await readdir(path.join(ROOT, ".migration"));
const fragments = await Promise.all(
	fragmentFiles
		.filter((file) => file.endsWith(".md") && file !== "README.md")
		.map(async (file) => readFile(path.join(ROOT, ".migration", file), "utf8")),
);
const stops = stopEveryRoleEntries(
	await readFile(path.join(ROOT, "MIGRATION.md"), "utf8"),
	fragments,
	previous.slice(1),
);
console.log(
	`Upgrading ${previous} (${previousCommit}) to ${commit}: ${stops.length > 0 ? `stopped, because ${stops.join("; ")}` : "rolling"}.`,
);

const cluster = async (cwd: string, ...args: string[]) =>
	run(process.execPath, [path.join(cwd, "scripts", "kubernetes-cluster.ts"), ...args], { cwd });
const settings = (...pairs: string[]) => pairs.flatMap((pair) => ["--set", pair]);

const checkout = await mkdtemp(path.join(tmpdir(), "previous-release-"));
await run("git", ["worktree", "add", "--detach", checkout, previousCommit], { cwd: ROOT });
try {
	// The previous release's scripts need packages; the candidate's install serves them for a few releases' drift.
	await symlink(path.join(ROOT, "node_modules"), path.join(checkout, "node_modules"), "dir");
	const previousImages = path.join(work, "previous-images.tsv");
	await run(
		process.execPath,
		["scripts/resolve-release-images.ts", previousCommit, previousImages],
		{
			cwd: checkout,
		},
	);

	console.log(`Installing ${previous} with its own chart and images.`);
	// The previous release, installed as that release installs.
	await cluster(
		checkout,
		"prepare",
		"--work",
		work,
		"--images",
		previousImages,
		"--commit",
		previousCommit,
		"--gateway",
	);
	await cluster(checkout, "gateway", "--work", work);
	await cluster(checkout, "install", "--work", work, "--profile", profile);
	await cluster(checkout, "verify");
	const previousRevision = await revision();

	// The candidate prepares a copy of the work directory: phase 1 still applies the previous release's values.
	const candidateWork = `${work}-candidate`;
	await cp(work, candidateWork, { recursive: true });
	await cluster(
		ROOT,
		"prepare",
		"--work",
		candidateWork,
		"--images",
		images,
		"--commit",
		commit,
		"--gateway",
	);

	// A chart with server.stopped stops the servers and keeps the count that sizes PostgreSQL. An older chart stops them
	// with a count of 0, so every phase pins max_connections to the larger of the running value and the new chart's
	// own value, as docs/admin/upgrade.mdx says. A larger new value restarts PostgreSQL once, in phase 1.
	const previousValues: unknown = parse(
		await readFile(path.join(checkout, "deploy", "chart", "hephaestus", "values.yaml"), "utf8"),
	);
	const canStop =
		isRecord(previousValues) &&
		isRecord(previousValues.server) &&
		"stopped" in previousValues.server;
	const pin: string[] = [];
	let restartsInPhase1 = false;
	if (stops.length > 0 && !canStop) {
		const running = await output("kubectl", [
			"--namespace",
			NAMESPACE,
			"exec",
			`${RELEASE}-postgresql-0`,
			"-c",
			"postgresql",
			"--",
			"psql",
			"-U",
			"postgres",
			"-At",
			"-c",
			"SHOW max_connections",
		]);
		const source = Number(running.trim());
		const target = await derivedConnections(ROOT, candidateWork, profile, "server.stopped=true");
		await checkClusterProfile(checkout, candidateWork);
		restartsInPhase1 = target > source;
		pin.push(`postgresql.parameters.max_connections=${Math.max(source, target)}`);
	}

	const phase1 = [
		canStop ? "server.stopped=true" : "server.replicas=0",
		"migration.enabled=false",
		...pin,
	];
	// The new chart first applies in phase 2, or in the rolling upgrade. Each release builds the PostgreSQL image again
	// with the distribution's current security fixes, so its image may change there and restart PostgreSQL once. Any
	// other change to its Pod template fails the drill, and phase 3 must keep PostgreSQL.
	const firstApply = stops.length > 0 ? ["server.stopped=true", ...pin] : pin;
	const postgresqlChanges = changedPostgresql(
		await render(checkout, work, profile, ...(stops.length > 0 ? phase1 : [])),
		await render(ROOT, candidateWork, profile, ...firstApply),
	);
	const firstApplyCommand = postgresqlChanges.length > 0 ? "install" : "upgrade";
	if (postgresqlChanges.length > 0) {
		console.log(
			`The new chart changes PostgreSQL's ${postgresqlChanges.join(", ")}, so PostgreSQL restarts once when it first applies.`,
		);
	}

	if (stops.length > 0) {
		console.log(
			`Phase 1: the current chart stops every role${canStop ? "" : `, with ${pin.join(", ")}`}.`,
		);
		// Phase 1: the current chart stops every role, and no old Pod remains. A larger pin restarts PostgreSQL here, so the
		// check that PostgreSQL survives starts with phase 2.
		await cluster(
			checkout,
			restartsInPhase1 ? "install" : "upgrade",
			"--work",
			work,
			"--profile",
			profile,
			...settings(...phase1),
		);
		await run("kubectl", [
			"--namespace",
			NAMESPACE,
			"wait",
			"--for=delete",
			"pod",
			"--selector",
			"app.kubernetes.io/component=server",
			"--timeout=20m",
		]);
		// The phase also ends every sandbox Pod and Job of the old servers.
		await run("kubectl", [
			"--namespace",
			SANDBOXES,
			"wait",
			"--for=delete",
			"pods,jobs",
			"--all",
			"--timeout=20m",
		]);
	}
	if (stops.length > 0) {
		console.log("Phase 2: the new chart migrates with every role stopped.");
		// Phase 2: the new chart migrates with every role stopped; Helm waits for the migration Job.
		await cluster(
			ROOT,
			firstApplyCommand,
			"--work",
			candidateWork,
			"--profile",
			profile,
			...settings(...firstApply),
		);
	}
	console.log(
		stops.length > 0 ? "Phase 3: the new roles start." : "Rolling upgrade to the new chart.",
	);
	// Phase 3, or the rolling upgrade: the new roles start. Phase 3 must keep PostgreSQL.
	await cluster(
		ROOT,
		stops.length > 0 ? "upgrade" : firstApplyCommand,
		"--work",
		candidateWork,
		"--profile",
		profile,
		...settings(...pin),
	);
	await cluster(ROOT, "verify");
	console.log(`The chart of ${previous} upgraded to ${commit}.`);

	if (stops.length > 0) {
		console.log(`${previous} does not run on the new schema, so the drill checks no claim of it.`);
	} else {
		await previousReleaseClaims(previous, previousRevision);
		console.log(`Rolling forward to ${commit}.`);
		// If the new chart changed PostgreSQL, the rollback changed it back, and the new chart changes it again.
		await cluster(ROOT, firstApplyCommand, "--work", candidateWork, "--profile", profile);
		await cluster(ROOT, "verify");
	}
} finally {
	await run("git", ["worktree", "remove", "--force", checkout], { cwd: ROOT });
	await rm(checkout, { recursive: true, force: true });
}

/**
 * Returns the release to the Helm revision of the previous release, queues a review, and waits until a sandbox of the
 * previous release leases the review's run, which raises its lease epoch, and the claim takes the job out of the queue.
 * A sandbox claims its own run when it pulls, so only a cluster with sandboxes proves this claim
 * (docs/contributor/database-migration.mdx § Claim of a queued review). Hibernate does not validate the claim's native
 * queries, so only a claim proves them. The rollback applies the whole previous revision: its migration Job runs again
 * and applies nothing, and a changed PostgreSQL template restarts PostgreSQL.
 */
async function previousReleaseClaims(release: string, previousRevision: number) {
	console.log(
		`Returning the server to ${release}, revision ${previousRevision}, on the migrated schema.`,
	);
	const listed = await output("kubectl", [
		"--namespace",
		NAMESPACE,
		"get",
		"pods",
		"--selector",
		"app.kubernetes.io/component=server",
		"--output",
		"name",
	]);
	const candidateServers = listed.split("\n").filter(Boolean);
	await run("helm", [
		"rollback",
		RELEASE,
		String(previousRevision),
		"--namespace",
		NAMESPACE,
		"--wait=watcher",
		"--wait-for-jobs",
		"--timeout",
		"20m",
	]);
	// A draining server claims no run. Once the candidate's servers are gone, only the previous release can claim.
	if (candidateServers.length > 0) {
		await run("kubectl", [
			"--namespace",
			NAMESPACE,
			"wait",
			"--for=delete",
			...candidateServers,
			"--timeout=10m",
		]);
	}

	// The candidate's own fixture, and the run, the job and the item's job as its enqueue path writes them, but with no
	// frozen model: the workspace binds none, so the config snapshot stays empty. No provider holds the reviewed work, so
	// the previous release ends the job after it claimed it. The seeded upgrade binds a model through its API.
	const job = randomUUID();
	const runId = randomUUID();
	try {
		await psql(
			await readFile(
				path.join(
					ROOT,
					"server/application/src/test/resources/db/practice-native/result-parent.sql",
				),
				"utf8",
			),
		);
		await psql(`BEGIN;
			INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key)
			VALUES ('${runId}', 978001, 'REVIEW', 'BATCH', 'review:${REVIEW}');
			INSERT INTO review_job (id, review_id, workspace_id, run_id, submitted_at, trace_id, input, config_snapshot)
			VALUES ('${job}', '${REVIEW}', 978001, '${runId}', now(), '${job.replaceAll("-", "")}',
				'{"itemId":978006,"assignments":[{"reviewId":"${REVIEW}","practiceId":978004,"itemId":978006}]}', '{}');
			UPDATE review_item SET job_id = '${job}' WHERE review_id = '${REVIEW}';
			COMMIT;`);
	} catch (error) {
		throw new Error(
			`The queued review does not fit the candidate's schema. Fix the drill's SQL, not the release: ${error instanceof Error ? error.message : String(error)}`,
			{ cause: error },
		);
	}

	const read = async () =>
		asRecord(
			parseJson(
				await psql(
					`SELECT json_build_object('epoch', r.lease_epoch, 'run', r.state, 'stop', r.stop_reason, 'job', j.status, 'reason', j.cancellation_reason, 'error', j.error_message) FROM agent_run r JOIN review_job j ON j.run_id = r.id WHERE r.id = '${runId}'`,
				),
			),
			"queued review",
		);
	const deadline = Date.now() + 10 * 60_000;
	for (;;) {
		const state = await read();
		// A claim that cannot lock the queued job defers the run with a raised epoch, so the job must leave the queue too.
		if (typeof state.epoch === "number" && state.epoch > 0 && state.job !== "QUEUED") {
			console.log(
				`${release} claimed the queued review on the migrated schema: ${JSON.stringify(state)}.`,
			);
			return;
		}
		if (Date.now() > deadline) {
			throw new Error(
				`${release} did not claim the queued review on the migrated schema in 10 minutes: ${JSON.stringify(state)}`,
			);
		}
		await sleep(5000);
	}
}

/** The chart of a checkout rendered offline with a profile, a work directory's values and settings. */
async function render(tree: string, workValues: string, chartProfile: string, ...pairs: string[]) {
	const chart = path.join(tree, "deploy", "chart", "hephaestus");
	return output("helm", [
		"template",
		RELEASE,
		chart,
		"--namespace",
		NAMESPACE,
		"--values",
		path.join(chart, "profiles", `${chartProfile}.yaml`),
		"--values",
		path.join(workValues, "values.yaml"),
		...settings(...pairs),
	]);
}

/** The max_connections that a chart derives for these values, from its rendered PostgreSQL arguments. */
async function derivedConnections(
	tree: string,
	workValues: string,
	chartProfile: string,
	...pairs: string[]
) {
	const rendered = await render(tree, workValues, chartProfile, ...pairs);
	const derived = /max_connections=(?<count>\d+)/u.exec(rendered)?.groups?.count;
	if (derived === undefined) {
		throw new Error(`The chart in ${tree} rendered no max_connections`);
	}
	return Number(derived);
}

/**
 * On the cluster profile the new chart needs more connections than the previous one derives, which the dev profile's
 * floor hides. For one to three servers, the pin of docs/admin/upgrade.mdx must pass all three phases.
 */
async function checkClusterProfile(previousTree: string, candidateValues: string) {
	for (const servers of [1, 2, 3]) {
		const count = `server.replicas=${servers}`;
		const pinned = Math.max(
			await derivedConnections(previousTree, work, "cluster", count),
			await derivedConnections(ROOT, candidateValues, "cluster", count),
		);
		const pin = `postgresql.parameters.max_connections=${pinned}`;
		// Each phase renders, so the chart accepts the pin: the new chart refuses one below its own need.
		await render(
			previousTree,
			work,
			"cluster",
			"server.replicas=0",
			"migration.enabled=false",
			pin,
		);
		await render(ROOT, candidateValues, "cluster", count, "server.stopped=true", pin);
		await render(ROOT, candidateValues, "cluster", count, pin);
		console.log(`Cluster profile, ${servers} servers: the pin ${pinned} passes all three phases.`);
	}
}
