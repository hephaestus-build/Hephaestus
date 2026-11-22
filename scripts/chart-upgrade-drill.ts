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

import {
	NAMESPACE,
	portForward,
	psql,
	RELEASE,
	revision,
	SANDBOXES,
	signInPod,
} from "./kubernetes-cluster.ts";
import { syntheticApi, syntheticSession } from "./lib/capacity-seed.ts";
import { changedPostgresql } from "./lib/chart-postgresql.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { latestReleaseOf } from "./lib/previous-release.ts";
import { output, run } from "./lib/process.ts";

const ROOT = path.resolve(import.meta.dirname, "..");
/** The candidate's review fixture, and the review it seeds with its practice and item. */
const FIXTURE = "server/application/src/test/resources/db/practice-native/result-parent.sql";
const REVIEW = "00000000-0000-0000-0000-000000978011";
/** The review model's endpoint, which nothing answers: the attempt ends before it calls a model. */
const MODEL_URL = "http://127.0.0.1:9/v1";
const PROTOCOL = "openai-completions";
const SIGN_IN = `${RELEASE}-claim-sign-in`;

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
	// with the distribution's current security fixes, so its image may change there and restart PostgreSQL once. Its
	// arguments, and the memory the chart derives from them, may change with the release in the same restart. Any other
	// change to its Pod template fails the drill, and phase 3 must keep PostgreSQL.
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
 * Returns the release to the Helm revision of the previous release, queues a review, and waits until the previous
 * release claims it: a sandbox leases the review's run, and the claim starts the job. A sandbox claims its own run when
 * it pulls, so only a cluster with sandboxes proves this claim (docs/contributor/database-migration.mdx § Claim of a
 * queued review). Hibernate does not validate the claim's native queries, so only a claim proves them. The rollback
 * applies the whole previous revision: its migration Job runs again and applies nothing, and a changed PostgreSQL
 * template restarts PostgreSQL.
 */
async function previousReleaseClaims(release: string, previousRevision: number) {
	console.log(
		`Returning the release to ${release}, revision ${previousRevision}, on the migrated schema.`,
	);
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
	const deployment = parseJson(
		await output("kubectl", [
			"--namespace",
			NAMESPACE,
			"get",
			"deployment",
			`${RELEASE}-server`,
			"--output",
			"json",
		]),
	);
	// A server that does not run the restored image is the candidate's, and it drains: a draining server claims no run.
	// Once those servers are gone, only the previous release can claim.
	const image = serverImage(deployment);
	const servers = asArray(
		asRecord(
			parseJson(
				await output("kubectl", [
					"--namespace",
					NAMESPACE,
					"get",
					"pods",
					"--selector",
					"app.kubernetes.io/component=server",
					"--output",
					"json",
				]),
			),
			"server Pods",
		).items,
		"server Pods",
	);
	const candidateServers = servers
		.filter((pod) => serverImage(pod) !== image)
		.map(
			(pod) =>
				`pod/${asString(asRecord(asRecord(pod, "Pod").metadata, "Pod metadata").name, "Pod name")}`,
		);
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

	try {
		await psql(await readFile(path.join(ROOT, FIXTURE), "utf8"));
		// The developer whose work the review is about, as the candidate's request path records it.
		await psql(
			`UPDATE review_item SET detail = jsonb_build_object('developerId', 978003) WHERE review_id = '${REVIEW}'`,
		);
	} catch (error) {
		throw new Error(
			`The candidate's fixture ${FIXTURE} does not fit the migrated schema: ${error instanceof Error ? error.message : String(error)}`,
			{ cause: error },
		);
	}
	const modelId = await bindReviewModel(deployment);

	// The method, the job and its run as the candidate's enqueue path writes them (ReviewServiceImpl pins the method,
	// ReviewJobService.enqueue freezes the snapshot): the one review slot holds the bound model with the default judgment
	// options, and the job freezes the model with its NO_CHARGE price.
	const job = randomUUID();
	const runId = randomUUID();
	const bound = `FROM llm_model m
		JOIN llm_model_price p ON p.model_id = m.id AND p.effective_to IS NULL
		JOIN workspace_model_assignment b ON b.instance_model_id = m.id AND b.workspace_id = 978001
			AND b.purpose = 'PRACTICE_REVIEW'
		WHERE m.id = ${modelId}`;
	await psql(`BEGIN;
		WITH slots AS (SELECT jsonb_build_array(jsonb_build_object('slot', 'review', 'kind', 'CHAT', 'model',
				jsonb_build_object('modelId', 'instance:' || m.id, 'providerProtocol', '${PROTOCOL}',
					'providerModelId', m.upstream_model_id, 'reasoningEffort', lower(m.reasoning_effort),
					'options', jsonb_build_object('practiceModelCalls', 36, 'practiceOutputTokens', 48000)))) AS models
			${bound})
		UPDATE review_practice SET method = jsonb_build_object('epoch', 1, 'modelDigest', review_model_digest(slots.models),
			'models', slots.models)
		FROM slots WHERE review_id = '${REVIEW}';
		INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key)
		VALUES ('${runId}', 978001, 'REVIEW', 'BATCH', 'review:${REVIEW}');
		INSERT INTO review_job (id, review_id, workspace_id, run_id, submitted_at, trace_id, input, config_snapshot)
		SELECT '${job}', '${REVIEW}', 978001, '${runId}', now(), '${job.replaceAll("-", "")}',
			'{"itemId":978006,"assignments":[{"reviewId":"${REVIEW}","practiceId":978004,"itemId":978006}]}',
			jsonb_build_object('schemaVersion', 10, 'contextWindow', null, 'maxOutputTokens', null,
				'timeoutSeconds', b.timeout_seconds, 'model', jsonb_build_object('apiProtocol', '${PROTOCOL}',
					'baseUrl', '${MODEL_URL}', 'upstreamModelId', m.upstream_model_id, 'connectionScope', 'INSTANCE',
					'connectionId', m.connection_id, 'modelId', m.id, 'workspaceId', 978001,
					'dataHandlingTier', b.data_handling_tier, 'reasoningEffort', m.reasoning_effort,
					'forcedToolChoice', COALESCE(m.forced_tool_choice, false),
					'priceSnapshot', jsonb_build_object('fundingSource', 'INSTANCE', 'pricingState', 'NO_CHARGE',
						'appliedPriceId', p.id, 'appliedWorkspaceModelId', null,
						'per1mInputUsd', p.per_1m_input_usd, 'per1mOutputUsd', p.per_1m_output_usd,
						'per1mCacheReadUsd', p.per_1m_cache_read_usd, 'per1mCacheWriteUsd', p.per_1m_cache_write_usd)))
		${bound};
		UPDATE review_item SET job_id = '${job}' WHERE review_id = '${REVIEW}';
		COMMIT;`);

	// The job's start is the claim's last write: the lease, the job locks, the member and model checks, the budget,
	// the admission and the review's start all ran before it, in one transaction.
	const read = async () => {
		const row = await psql(
			`SELECT json_build_object('job', j.status, 'started', j.started_at, 'epoch', r.lease_epoch, 'run', r.state,
				'stop', r.stop_reason, 'reason', j.cancellation_reason, 'error', j.error_message,
				'preparation', j.capture -> 'preparationFailure')
			FROM review_job j LEFT JOIN agent_run r ON r.id = j.run_id WHERE j.id = '${job}'`,
		);
		return asRecord(parseJson(row === "" ? "null" : row), `review job ${job}`);
	};
	const deadline = Date.now() + 10 * 60_000;
	for (;;) {
		const state = await read();
		if (state.started !== null) {
			console.log(
				`${release} claimed the queued review on the migrated schema: ${JSON.stringify(state)}.`,
			);
			return;
		}
		if (state.job !== "QUEUED") {
			throw new Error(
				`${release} ended the queued review without starting it: ${JSON.stringify(state)}`,
			);
		}
		if (Date.now() > deadline) {
			throw new Error(
				`${release} did not claim the queued review on the migrated schema in 10 minutes: ${JSON.stringify(state)}`,
			);
		}
		await sleep(5000);
	}
}

/** The image of the server container in a Deployment or a Pod. */
function serverImage(workload: unknown): string {
	const root = asRecord(workload, "server workload");
	const spec = asRecord(root.spec, "server spec");
	const podSpec =
		"template" in spec
			? asRecord(asRecord(spec.template, "server template").spec, "server Pod spec")
			: spec;
	const containers = asArray(podSpec.containers, "server containers").map((container) =>
		asRecord(container, "server container"),
	);
	const server = containers.find((container) => container.name === "server") ?? containers[0];
	return asString(server?.image, "server image");
}

/**
 * Binds a review model to the fixture's workspace through the previous release's own API, as the seeded upgrade does:
 * an instance connection on an endpoint that nothing answers, with a NO_CHARGE price. The release's servers refuse the
 * e2e sign-in, so a one-shot Pod of the previous release's server template signs the instance administrator in.
 */
async function bindReviewModel(deployment: unknown): Promise<number> {
	await run("kubectl", [
		"--namespace",
		NAMESPACE,
		"delete",
		"pod",
		SIGN_IN,
		"--ignore-not-found",
		"--wait=true",
	]);
	await output("kubectl", ["apply", "--filename", "-"], {
		input: JSON.stringify(signInPod(deployment, SIGN_IN)),
	});
	let forward: { port: number; stop: () => void } | undefined;
	try {
		await run("kubectl", [
			"--namespace",
			NAMESPACE,
			"wait",
			"--for=jsonpath={.status.containerStatuses[0].ready}=true",
			`pod/${SIGN_IN}`,
			"--timeout=10m",
		]);
		forward = await portForward(SIGN_IN, 8080);
		const base = `http://127.0.0.1:${forward.port}/api`;
		const token = await syntheticSession(base, "root", true);
		await psql(`INSERT INTO membership (workspace_id, account_id, role)
			SELECT 978001, id, 'ADMIN' FROM account WHERE primary_email = 'root@dev.invalid' ON CONFLICT DO NOTHING`);
		const api = syntheticApi(base, token);
		const connection = asRecord(
			await api("POST", "/admin/llm/connections", {
				displayName: "Claim drill",
				baseUrl: MODEL_URL,
				apiProtocol: PROTOCOL,
				apiKey: "claim-drill-key",
				enabled: true,
			}),
			"connection",
		);
		const model = asRecord(
			await api("POST", `/admin/llm/connections/${String(connection.id)}/models`, {
				displayName: "Claim drill model",
				upstreamModelId: "claim-drill-model",
			}),
			"model",
		);
		const modelId = Number(model.id);
		await api("PUT", `/admin/llm/models/${modelId}/price`, {
			pricingMode: "NO_CHARGE",
			note: "Drill model",
		});
		await api("PATCH", `/admin/llm/models/${modelId}`, { enabled: true });
		await api("PUT", `/admin/llm/models/${modelId}/sharing`, { visibility: "PUBLIC" });
		await api("PUT", "/workspaces/native-results/model-assignments/PRACTICE_REVIEW", {
			instanceModelId: modelId,
			enabled: true,
		});
		return modelId;
	} catch (error) {
		const logs = await output("kubectl", [
			"--namespace",
			NAMESPACE,
			"logs",
			SIGN_IN,
			"--tail=200",
		]).catch(String);
		console.log(`The sign-in Pod's log:\n${logs}`);
		throw error;
	} finally {
		forward?.stop();
		await run("kubectl", [
			"--namespace",
			NAMESPACE,
			"delete",
			"pod",
			SIGN_IN,
			"--ignore-not-found",
			"--wait=false",
		]);
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
