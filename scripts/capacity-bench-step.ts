/** TEST ONLY. Record one isolated-release admission step. Never changes the shared installation. */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import { parseArgs } from "node:util";

import { parseReplay } from "./lib/capacity-replay.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

const { values } = parseArgs({
	options: {
		label: { type: "string" },
		users: { type: "string" },
		admission: { type: "string" },
		"operator-state": { type: "string" },
	},
});
const label = asString(values.label, "--label");
const users = Number(values.users);
const admission = Number(values.admission);
const operatorStatePath = asString(values["operator-state"], "--operator-state");
if (
	!/^[a-z0-9-]+$/u.test(label) ||
	![40, 80, 120].includes(users) ||
	![8, 16, 32, 48].includes(admission)
) {
	throw new Error("Supply --label, --users 40|80|120, --admission 8|16|32|48 and --operator-state");
}
async function operatorCheck() {
	const state = asRecord(parseJson(await readFile(operatorStatePath, "utf8")), "operator state");
	if (state.state !== "CLEAR" || typeof state.source !== "string" || state.source.trim() === "") {
		throw new Error("Operator release state is not explicitly CLEAR");
	}
	if (
		typeof state.stopAt !== "string" ||
		!Number.isFinite(Date.parse(state.stopAt)) ||
		Date.now() >= Date.parse(state.stopAt)
	) {
		throw new Error("Operator window is missing or past its load-stop deadline");
	}
	return state;
}
const directory = `tmp/capacity-bench/sweep/${label}`;
await mkdir("tmp/capacity-bench/sweep", { recursive: true });
await mkdir(directory, { mode: 0o700 });
const quote = (text: string) => `'${text.replaceAll("'", String.raw`'\''`)}'`;
const remote = async (command: string) => output("remote-exec", ["hephaestus-staging", command]);
async function capture(command: string) {
	try {
		return { ok: true, stdout: await remote(command), error: null };
	} catch (error) {
		return {
			ok: false,
			stdout: isRecord(error) && typeof error.stdout === "string" ? error.stdout : "",
			error:
				error instanceof Error
					? (error.message.split("\n")[0] ?? "Remote command failed")
					: "Remote command failed",
		};
	}
}
const collectionFailures: { artifact: string; error: string | null }[] = [];
async function collect(artifact: string, command: string) {
	const result = await capture(command);
	await writeFile(`${directory}/${artifact}`, result.stdout);
	if (!result.ok) {
		collectionFailures.push({ artifact, error: result.error });
	}
	return result;
}
const driver = "sudo -n kubectl exec -n capacity-bench-20261030 deploy/bench-server -c driver --";
const queueQuery =
	'query { repository(owner:"hephaestus-build", name:"Hephaestus") { defaultBranchRef { target { oid } } mergeQueue(branch:"main") { entries(first:100) { nodes { pullRequest { number } } } } } }';
async function queueCheck() {
	const operatorState = await operatorCheck();
	const measuredCommit = asString(operatorState.measuredCommit, "operator measuredCommit");
	if (!/^[a-f0-9]{40}$/u.test(measuredCommit)) {
		throw new Error("Operator measuredCommit must be a complete commit SHA");
	}
	const result = asRecord(
		parseJson(await output("gh", ["api", "graphql", "-f", `query=${queueQuery}`])),
		"GitHub",
	);
	const repository = asRecord(asRecord(result.data, "data").repository, "repository");
	const entries = asArray(
		asRecord(asRecord(repository.mergeQueue, "queue").entries, "entries").nodes,
		"nodes",
	);
	if (entries.length > 0 && operatorState.mergeQueueWaived !== true) {
		throw new Error("Merge queue is busy; no recorded load starts");
	}
	const releases = asArray(
		parseJson(
			await remote(
				"sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm list -n hephaestus -o json",
			),
		),
		"Helm releases",
	).map((row) => asRecord(row, "release"));
	const helm = releases.find((release) => release.name === "hephaestus");
	if (helm === undefined || String(helm.revision) !== String(operatorState.helmRevision)) {
		throw new Error("Frozen staging Helm revision changed");
	}
	return {
		checkedAt: new Date().toISOString(),
		operatorState,
		helm: { revision: helm.revision, chart: helm.chart, status: helm.status },
		measuredCommit,
		repository,
		mergeQueueEmpty: entries.length === 0,
		mergeQueueWaived: operatorState.mergeQueueWaived === true,
		releaseIdleConfirmed: operatorState.releaseIdleConfirmed === true,
		scope: "isolated-release",
		sharedRolesStopped: operatorState.sharedRolesStopped === true,
		toolMode: "none",
		driver: "typed-typescript-sse",
		users,
		admission,
	};
}
const before = await queueCheck();
const providerConfig = asRecord(
	asRecord(
		parseJson(
			await remote("sudo -n kubectl get configmap replay -n capacity-bench-20261030 -o json"),
		),
		"provider config",
	).data,
	"provider data",
);
const providerReplay = parseReplay(
	parseJson(asString(providerConfig["replay.json"], "replay.json")),
);
if (
	providerReplay.samples.some(
		(sample) =>
			sample.ttftMs !== 3000 ||
			sample.outputTokens !== 500 ||
			sample.durationMs !== 13_000 ||
			sample.status !== 200,
	)
) {
	throw new Error(
		"Admission sweep requires the 3 s / 500 token / 50 token-per-second / zero-429 replay",
	);
}
await writeFile(`${directory}/provider-replay.json`, JSON.stringify(providerReplay, null, 2));
const fake = asRecord(
	parseJson(
		await remote("sudo -n kubectl get deployment replay -n capacity-bench-20261030 -o json"),
	),
	"fake deployment",
);
const fakePod = asRecord(asRecord(asRecord(fake.spec, "spec").template, "template").spec, "pod");
const fakeCommand = asArray(
	asRecord(asArray(fakePod.containers, "containers")[0], "fake").command,
	"command",
);
const toolFlag = fakeCommand.indexOf("--tool-mode");
if (toolFlag === -1 || fakeCommand[toolFlag + 1] !== "none") {
	throw new Error("Admission sweep requires --tool-mode none on the fake");
}
const worker = asRecord(
	parseJson(
		await remote("sudo -n kubectl get deploy bench-worker -n capacity-bench-20261030 -o json"),
	),
	"worker",
);
const workerContainers = asArray(
	asRecord(asRecord(asRecord(worker.spec, "spec").template, "template").spec, "pod").containers,
	"containers",
);
const workerEnv = asArray(asRecord(workerContainers[0], "worker container").env, "worker env").map(
	(row) => asRecord(row, "variable"),
);
if (
	workerEnv.find((row) => row.name === "HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX")?.value !==
	String(admission)
) {
	throw new Error("Worker admission does not match requested measurement");
}
await writeFile(`${directory}/queue-before.json`, JSON.stringify(before, null, 2));
const preflight = await collect(
	"preflight-cleanup.json",
	`${driver} node /bench/scripts/capacity-bench-stop.ts`,
);
const preflightCleanup = asRecord(parseJson(preflight.stdout), "synthetic cleanup");
await writeFile(`${directory}/preflight-cleanup.json`, JSON.stringify(preflightCleanup));
if (!preflight.ok || preflightCleanup.failures !== 0 || preflightCleanup.remaining !== 0) {
	throw new Error("Synthetic owners are not idle; no load starts");
}
await remote(
	`sudo -n kubectl set env -n capacity-bench-20261030 deployment/replay CAPACITY_RUN_LABEL=${label}`,
);
await remote(
	"sudo -n kubectl rollout status -n capacity-bench-20261030 deployment/replay --timeout=60s",
);
const resetSql = await readFile("scripts/capacity-bench/reset-conversations.sql", "utf8");
await writeFile(
	`${directory}/reset.log`,
	await remote(
		`sudo -n kubectl exec -i -n capacity-bench-20261030 bench-postgresql-0 -- sh -c 'exec psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -At -v ON_ERROR_STOP=1' <<'SQL'\n${resetSql}\nSQL`,
	),
);
// Setup can take time. Recheck the operator and queue after the fake rollout and fixture reset.
await queueCheck();
const encoded = Buffer.from(JSON.stringify(before)).toString("base64");
await remote(
	`${driver} node -e ${quote(`require('fs').writeFileSync('/bench/quiet-${label}.json',Buffer.from('${encoded}','base64'))`)}`,
);
async function sharedSnapshot() {
	const rows = await remote(
		"sudo -n kubectl get deploy -n hephaestus --no-headers -o custom-columns=NAME:.metadata.name,GENERATION:.metadata.generation,REPLICAS:.spec.replicas",
	);
	return rows
		.trim()
		.split("\n")
		.map((line) => {
			const [name, generation, replicas] = line.trim().split(/\s+/u);
			if (
				name === undefined ||
				!/^[a-z0-9-]+$/u.test(name) ||
				!/^\d+$/u.test(generation ?? "") ||
				!/^\d+$/u.test(replicas ?? "")
			) {
				throw new Error("Invalid shared deployment metadata");
			}
			return { name, generation: Number(generation), replicas: Number(replicas) };
		});
}
const sharedRoles = await sharedSnapshot();
await writeFile(`${directory}/shared-before.json`, JSON.stringify(sharedRoles, null, 2));

const sampler = spawn(
	process.execPath,
	[
		"scripts/kubernetes-footprint.ts",
		"--samples",
		"1500",
		"--interval-ms",
		"1000",
		"--bench-postgres",
		"--minimum-available-mib",
		"1280",
		"--out",
		`${directory}/use.json`,
	],
	{ stdio: ["ignore", "pipe", "pipe"] },
);
sampler.stderr.pipe(createWriteStream(`${directory}/sampler.stderr`));
const stopFailures: string[] = [];
const state = {
	headroomStop: false,
	stopRequested: false,
	loadFinished: false,
	samplerFailed: false,
	stopFailures,
};
const samplerReady = Promise.withResolvers<undefined>();
async function signalChild() {
	try {
		await remote(`${driver} node /bench/scripts/capacity-bench-signal.ts --label ${label}`);
	} catch {
		state.stopFailures.push("Failed to signal isolated driver");
	}
}
async function stopLoad() {
	state.stopRequested = true;
	stopPromise ??= signalChild();
	await stopPromise;
}
let stopPromise: Promise<void> | undefined;
sampler.stdout.on("data", (chunk: Buffer) => {
	const line = chunk.toString();
	process.stdout.write(line);
	if (line.includes("SAMPLER_READY")) {
		samplerReady.resolve(undefined);
	}
	if (line.includes("HEADROOM_STOP")) {
		state.headroomStop = true;
		void stopLoad();
	}
});
const samplerCompletion = Promise.withResolvers<undefined>();
sampler.once("error", samplerReady.reject);
sampler.once("close", (code) => {
	if (!state.loadFinished || code !== 0) {
		state.samplerFailed = true;
		samplerReady.reject(new Error(`Sampler exited ${String(code)}`));
		void stopLoad();
	}
	samplerCompletion.resolve(undefined);
});
const queueDuring: unknown[] = [];
let checkingQueue = false;
async function monitorQueue() {
	if (checkingQueue) {
		return;
	}
	checkingQueue = true;
	try {
		const check = await queueCheck();
		const currentShared = await sharedSnapshot();
		if (
			currentShared.some(
				(row) => sharedRoles.find((role) => role.name === row.name)?.generation !== row.generation,
			)
		) {
			throw new Error("Shared release changed during recorded load");
		}
		queueDuring.push(check);
	} catch (error) {
		queueDuring.push({
			at: new Date().toISOString(),
			contaminated: true,
			reason: error instanceof Error ? error.message.split("\n")[0] : "Guard check failed",
		});
		await stopLoad();
	} finally {
		checkingQueue = false;
	}
}
const deadlineTimer = setTimeout(
	() => {
		void stopLoad();
	},
	Math.max(0, Date.parse(String(before.operatorState.stopAt)) - Date.now()),
);
const interrupted = () => {
	void stopLoad();
};
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
	process.on(signal, interrupted);
}
const queueMonitor = setInterval(() => {
	void monitorQueue();
}, 30_000);
try {
	await samplerReady.promise;
	if (state.stopRequested || state.headroomStop || state.samplerFailed) {
		throw new Error("Guard stopped the run before load");
	}
	process.stdout.write(`${label}: starting ${users} users × 3 turns, admission ${admission}\n`);
	const started = new Date().toISOString();
	const result = await remote(
		`${driver} env CAPACITY_STOP_AT=${quote(String(before.operatorState.stopAt))} COURSE_SSE=1 COURSE_USERS=${users} COURSE_TURNS=3 CAPACITY_TURN_ADMISSION=${admission} CAPACITY_TOOL_MODE=none CAPACITY_PROVIDER_PROVENANCE=${providerReplay.provenance} node /bench/scripts/capacity-bench-run.ts --label ${label} --mode recorded --quiet-window /bench/quiet-${label}.json`,
	).catch((error: unknown) => {
		if (error instanceof Error) {
			return error.message.split("\n")[0] ?? "load failed";
		}
		throw error;
	});
	process.stdout.write(`${result}\n`);
	state.loadFinished = true;
	await writeFile(
		`${directory}/result.json`,
		JSON.stringify({
			started,
			finished: new Date().toISOString(),
			headroomStop: state.headroomStop,
			stopRequested: state.stopRequested,
			samplerFailed: state.samplerFailed,
			result,
		}),
	);
} catch (error) {
	state.stopRequested = true;
	await writeFile(
		`${directory}/setup-error.json`,
		JSON.stringify({ error: error instanceof Error ? error.message : "Run setup failed" }),
	);
} finally {
	state.loadFinished = true;
	clearInterval(queueMonitor);
	clearTimeout(deadlineTimer);
	if (stopPromise === undefined) {
		await signalChild();
	} else {
		await stopPromise;
	}
	await writeFile(`${directory}/queue-during.json`, JSON.stringify(queueDuring, null, 2));
	sampler.kill("SIGTERM");
	await samplerCompletion.promise;
	await collect("cleanup.json", `${driver} node /bench/scripts/capacity-bench-stop.ts`);
}
const archive = spawn(
	"remote-exec",
	["hephaestus-staging", `${driver} tar czf - -C /bench results/${label} load-tests`],
	{ stdio: ["ignore", "pipe", "inherit"] },
);
const archiveCompletion = Promise.withResolvers<number | null>();
archive.once("error", archiveCompletion.reject);
archive.once("close", archiveCompletion.resolve);
try {
	const [, code] = await Promise.all([
		pipeline(archive.stdout, createWriteStream(`${directory}/driver.tgz`)),
		archiveCompletion.promise,
	]);
	if (code !== 0) {
		collectionFailures.push({ artifact: "driver.tgz", error: "Archive command failed" });
	}
} catch {
	collectionFailures.push({
		artifact: "driver.tgz",
		error: "Archive transport failed; retain the driver Pod",
	});
}
async function readArtifact(name: string) {
	try {
		return asRecord(parseJson(await readFile(`${directory}/${name}`, "utf8")), name);
	} catch {
		collectionFailures.push({ artifact: name, error: "Missing or invalid JSON receipt" });
		return {};
	}
}
await Promise.all([
	collect("run.json", `${driver} cat /bench/results/${label}/run.json`),
	collect("summary.json", `${driver} cat /bench/results/${label}/summary.json`),
]);
const run = await readArtifact("run.json");
const summary = await readArtifact("summary.json");
const runStart = typeof run.startedAt === "string" ? Date.parse(run.startedAt) : Number.NaN;
const startTimestamp = new Date(
	Number.isFinite(runStart) ? runStart : Date.parse(before.checkedAt),
).toISOString();
const sql = `SELECT json_build_object('requestId',request_id,'turnId',id,'createdAt',created_at,'startedAt',started_at,'finishedAt',finished_at,'status',status,'calls',llm_calls,'attempt',attempt) FROM mentor_turn WHERE workspace_id=986600 AND created_at >= '${startTimestamp}'::timestamptz ORDER BY created_at;`;
await Promise.all([
	collect(
		"turns.jsonl",
		`sudo -n kubectl exec -i -n capacity-bench-20261030 bench-postgresql-0 -- sh -c 'exec psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -At -v ON_ERROR_STOP=1' <<'SQL'\nBEGIN READ ONLY;\n${sql}\nCOMMIT;\nSQL`,
	),
	collect(
		"provider.jsonl",
		`sudo -n kubectl logs -n capacity-bench-20261030 deploy/replay --since-time=${startTimestamp}`,
	),
	collect(
		"worker.jsonl",
		`sudo -n kubectl logs -n capacity-bench-20261030 deploy/bench-worker --since-time=${startTimestamp}`,
	),
	collect("pods-app.json", "sudo -n kubectl get pods -n capacity-bench-20261030 -o json"),
	collect(
		"pods-sandbox.json",
		"sudo -n kubectl get pods -n capacity-bench-20261030-sandboxes -o json",
	),
	collect(
		"events-after.json",
		"sudo -n kubectl get events -n capacity-bench-20261030-sandboxes -o json",
	),
	collect("events-app-after.json", "sudo -n kubectl get events -n capacity-bench-20261030 -o json"),
]);
const podLists = await Promise.all([
	readArtifact("pods-app.json"),
	readArtifact("pods-sandbox.json"),
]);
const owned = podLists.flatMap((pods) =>
	asArray(pods.items ?? [], "items").map((p) => asRecord(p, "pod")),
);
await writeFile(
	`${directory}/pods-after.json`,
	JSON.stringify(
		owned.map((p) => ({ metadata: p.metadata, status: p.status })),
		null,
		2,
	),
);
try {
	await writeFile(`${directory}/queue-after.json`, JSON.stringify(await queueCheck(), null, 2));
} catch (error) {
	state.stopRequested = true;
	await writeFile(
		`${directory}/queue-after.json`,
		JSON.stringify({
			contaminated: true,
			reason: error instanceof Error ? error.message.split("\n")[0] : "Guard check failed",
		}),
	);
}
const footprint = await readArtifact("use.json");
const cleanup = await readArtifact("cleanup.json");
const qualified =
	!state.stopRequested &&
	!state.headroomStop &&
	!state.samplerFailed &&
	state.stopFailures.length === 0 &&
	collectionFailures.length === 0 &&
	run.exitCode === 0 &&
	summary.qualified === true &&
	cleanup.failures === 0 &&
	cleanup.remaining === 0;
await writeFile(
	`${directory}/verdict.json`,
	JSON.stringify(
		{
			qualified,
			...state,
			collectionFailures,
			driverExitCode: run.exitCode ?? null,
			cleanup,
			measuredCommit: before.measuredCommit,
			helm: before.helm,
		},
		null,
		2,
	),
);
if (state.stopFailures.length > 0) {
	process.stderr.write(`${state.stopFailures.join("\n")}\n`);
}
process.stdout.write(
	`${label}: saved; node peak ${String(footprint.nodePeakBytes)} bytes; headroom stop ${state.headroomStop}\n`,
);
const entries = await readdir(directory, { withFileTypes: true });
const checksums = await Promise.all(
	entries
		.filter((entry) => entry.isFile())
		.map(
			async (entry) =>
				`${createHash("sha256")
					.update(await readFile(`${directory}/${entry.name}`))
					.digest("hex")}  ${entry.name}`,
		),
);
await writeFile(`${directory}/SHA256SUMS`, `${checksums.join("\n")}\n`);
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
	process.off(signal, interrupted);
}
process.exitCode = qualified ? 0 : 1;
