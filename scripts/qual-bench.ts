/**
 * The capacity bench of a release: B3–B8, B10 and B11 of docs/contributor/qualify-a-release.mdx, over one or more
 * measured windows.
 *
 *   node scripts/qual-bench.ts --target k3d|staging [--minutes 35] [--rounds 1] [--round-gap-minutes 0] [--fixture <file>]
 *     [--memory-drill --images <release-images.tsv>] [--overview-url <url> --token-file <file>] [--statements <file>]
 *
 * Collectors run for the whole window (lib/qual-collect.ts). On staging, --fixture starts reviews through the session-free
 * trigger (lib/qual-trigger.ts). A measure without the load or the reading it needs is `not-run`, never a pass.
 */
import { parseArgs } from "node:util";

import { memoryDrill } from "./kubernetes-memory-drill.ts";
import {
	type Line,
	histogramQuantile,
	memorySplit,
	metricSeries,
	nodeSummary,
	podsSeen,
	podUsage,
	readLines,
	sqlMax,
} from "./lib/qual-analyze.ts";
import { COMMON, commitOf, commonFrom, mib, percentile, sleep } from "./lib/qual-args.ts";
import { items, path2, quantity, str } from "./lib/qual-cluster.ts";
import { collect } from "./lib/qual-collect.ts";
import { decide, Evidence, RULES } from "./lib/qual-evidence.ts";
import { judgeReads, type Named, probeOverview, readNamed, readsBefore } from "./lib/qual-reads.ts";
import { type RunRow, snapshot } from "./lib/qual-state.ts";
import { type Fixture, readFixture, startReviews } from "./lib/qual-trigger.ts";

const USAGE =
	"node scripts/qual-bench.ts --target k3d|staging [--minutes 35] [--rounds 1] [--round-gap-minutes 0] [--fixture <file>] [--memory-drill --images <tsv>] [--interval <s>] [--controller-namespace agent-sandbox-system] [--overview-url <url> --token-file <file>] [--statements <file>]";

const { values } = parseArgs({
	options: {
		...COMMON,
		minutes: { type: "string", default: "35" },
		fixture: { type: "string" },
		rounds: { type: "string", default: "1" },
		"round-gap-minutes": { type: "string", default: "0" },
		"memory-drill": { type: "boolean", default: false },
		images: { type: "string" },
		interval: { type: "string", default: "5" },
		"controller-namespace": { type: "string", default: "agent-sandbox-system" },
		"overview-url": { type: "string" },
		"token-file": { type: "string" },
		statements: { type: "string" },
	},
});
const common = commonFrom(values, USAGE);
const { cluster } = common;
const CONTROLLER = values["controller-namespace"];
// Parsed before the window, so that a bad file stops the run before it spends one.
const fixture: Fixture | null = values.fixture === undefined ? null : readFixture(values.fixture);
const named: Named[] = values.statements === undefined ? [] : readNamed(values.statements);
const intervalMs = Math.max(1, Number(values.interval)) * 1000;
const namespaces = [cluster.namespace, cluster.sandboxes, CONTROLLER];
const UNREAD_NEEDS =
	"a cgroup reading of every Pod (the node script found none for the Pods named)";

interface Window {
	from: number;
	to: number;
	label: string;
	loadNote: string;
}

/** One measured window: start the load, and collect until the window ends. */
async function measure(
	evidence: Evidence,
	label: string,
	reviews: number,
	minutes: number,
): Promise<Window> {
	const from = Date.now();
	const until = from + minutes * 60_000;
	let loadNote: string;
	const collecting = collect(cluster, evidence, until, {
		namespaces,
		intervals: {
			cgroupsMs: intervalMs,
			nodeMs: intervalMs,
			prometheusMs: 15_000,
			sqlMs: 15_000,
			limitsMs: 30_000,
			podsMs: 15_000,
		},
		limitComponents: ["server", "worker", "postgresql", "webhook"],
	});
	if (reviews > 0 && fixture !== null && cluster.target === "staging") {
		try {
			const started = await startReviews(cluster, evidence, fixture, reviews, label);
			loadNote = `${started.filter((entry) => entry.started).length}/${started.length} reviews started from the fixture`;
		} catch (error) {
			loadNote = `the trigger failed: ${error instanceof Error ? error.message : String(error)}`;
		}
	} else {
		loadNote =
			cluster.target === "k3d"
				? "no load: a k3d cluster has no SCM connection, so no review starts"
				: "no load: pass --fixture <file> to start reviews";
	}
	evidence.log(`${label}: ${loadNote}`);
	await collecting;
	return { from, to: Date.now(), label, loadNote };
}

interface Reduced {
	usage: ReturnType<typeof podUsage>;
	pods: ReturnType<typeof podsSeen>;
	/** The Pods of the last listing: what runs at the end of the window. */
	podsLast: ReturnType<typeof podsSeen>;
	split: ReturnType<typeof memorySplit>;
	nodes: ReturnType<typeof nodeSummary>;
	prometheus: Line[];
	sql: Line[];
	runs: RunRow[];
}

async function reduce(evidence: Evidence, window: Window): Promise<Reduced> {
	const read = (name: string) => readLines(evidence.dir, name, window.from, window.to + 1000);
	const state = await snapshot(cluster, (window.to - window.from) / 60_000 + 30);
	evidence.raw(`state-${window.label}.json`, state);
	const podLines = read("pods.jsonl");
	return {
		usage: podUsage(read("cgroups.jsonl")),
		pods: podsSeen(podLines),
		podsLast: podsSeen(podLines.slice(-1)),
		split: memorySplit(read("limits.jsonl")),
		nodes: nodeSummary(read("node.jsonl")),
		prometheus: read("prometheus.jsonl"),
		sql: read("sql.jsonl"),
		runs: state.runs.filter(
			(run) => run.started_at !== null && Date.parse(run.started_at) >= window.from,
		),
	};
}

/** A claimed review sandbox; not the worker's isolation probes, which carry the same kind. */
const isReviewPod = (name: string, pod: { sandboxKind: string }) =>
	name.startsWith(`${cluster.sandboxes}/`) &&
	pod.sandboxKind === "review" &&
	!name.includes("isolation-probe");

function judgeWindow(
	evidence: Evidence,
	window: Window,
	data: Reduced,
	allocatable: Allocatable[],
	suffix: string,
): void {
	const id = (name: string) => `${name}${suffix}`;
	const reviewRuns = data.runs.filter((run) => run.kind === "REVIEW");
	const ended = reviewRuns.filter((run) => run.ended_at !== null && run.started_at !== null);
	const loaded = ended.length > 0;
	const ttft = [
		"gen_ai_client_operation_duration_seconds",
		"llm_proxy_first_token_seconds",
		"llm_pool_first_token_seconds",
	]
		.map((name) => ({ name, ...histogramQuantile(data.prometheus, name, 0.95) }))
		.find((entry) => entry.count > 0);
	evidence.check(
		ttft === undefined
			? {
					id: id("B3"),
					title: "provider time to first token (recorded)",
					status: "not-run",
					needs: "a model call in the window",
					detail: "no first-token histogram moved in the window",
				}
			: {
					id: id("B3"),
					title: "provider time to first token (recorded)",
					status: "recorded",
					detail: `${ttft.name} p95 ≤ ${ttft.value ?? "n/a"} s over ${ttft.count} calls (bucket bound)`,
				},
	);
	// B4: the review sandboxes' memory against their request, and the review duration.
	const durations = ended.map(
		(run) => (Date.parse(run.ended_at ?? "") - Date.parse(run.started_at ?? "")) / 1000,
	);
	const reviewPeaks = Object.entries(data.pods)
		.filter(([name, pod]) => isReviewPod(name, pod))
		.map(([name, pod]) => ({
			name,
			peak: data.usage[name]?.peakLifetime ?? 0,
			unread: data.usage[name] === undefined,
			request: pod.requestMemory,
			oom: pod.lastTerminated.includes("OOMKilled"),
		}));
	const b4Title =
		"review sandbox memory.peak < its request and none OOM-killed; review duration recorded";
	if (loaded) {
		const overRequest = reviewPeaks.filter((pod) => pod.request > 0 && pod.peak >= pod.request);
		const oom = reviewPeaks.filter((pod) => pod.oom);
		const unread = reviewPeaks.filter((pod) => pod.unread);
		const held = overRequest.length === 0 && oom.length === 0;
		evidence.check({
			id: id("B4"),
			title: b4Title,
			status: decide(
				[
					[!held, "fail"],
					[unread.length > 0, "not-run"],
				],
				"pass",
			),
			...(held && unread.length > 0
				? { needs: `${UNREAD_NEEDS} (unread: ${unread.map((pod) => pod.name).join(", ")})` }
				: {}),
			rule: `${RULES} § B4`,
			detail: `${ended.length} reviews: duration p50 ${percentile(durations, 50)?.toFixed(1) ?? "n/a"} s, p95 ${percentile(durations, 95)?.toFixed(1) ?? "n/a"} s; ${reviewPeaks.length} review sandboxes, highest peak ${mib(Math.max(0, ...reviewPeaks.map((pod) => pod.peak)))}; over request ${overRequest.length}; OOM ${oom.length}`,
			data: { durations, reviewPeaks },
		});
	} else {
		evidence.check({
			id: id("B4"),
			title: b4Title,
			status: "not-run",
			needs: "reviews in the window (staging: --fixture)",
			detail: window.loadNote,
		});
	}
	const wait = histogramQuantile(data.prometheus, "llm_pool_permit_wait_seconds", 0.95);
	evidence.check(
		wait.count === 0
			? {
					id: id("B5"),
					title: "model permit wait p95 (recorded)",
					status: "not-run",
					needs: "a model permit grant in the window",
					detail: "no permit wait histogram moved in the window",
				}
			: {
					id: id("B5"),
					title: "model permit wait p95 (recorded)",
					status: "recorded",
					detail: `p95 ≤ ${wait.value ?? "n/a"} s over ${wait.count} grants (all bands)`,
				},
	);
	const byWorkspace = new Map<number, number[]>();
	for (const run of reviewRuns.filter((entry) => entry.started_at !== null)) {
		const list = byWorkspace.get(run.workspace_id) ?? [];
		list.push((Date.parse(run.started_at ?? "") - Date.parse(run.available_at)) / 1000);
		byWorkspace.set(run.workspace_id, list);
	}
	evidence.check(
		byWorkspace.size < 2
			? {
					id: id("B7"),
					title: "claim latency by workspace (recorded)",
					status: "not-run",
					needs: "reviews of two workspaces in the window",
					detail: `${byWorkspace.size} workspace(s) had reviews`,
				}
			: {
					id: id("B7"),
					title: "claim latency by workspace (recorded)",
					status: "recorded",
					detail: [...byWorkspace]
						.map(
							([workspace, list]) =>
								`workspace ${workspace}: p95 ${percentile(list, 95)?.toFixed(1) ?? "n/a"} s (n=${list.length})`,
						)
						.join("; "),
				},
	);
	judgeResources(evidence, data, loaded, allocatable, id);
}

function judgeResources(
	evidence: Evidence,
	data: Reduced,
	loaded: boolean,
	allocatable: Allocatable[],
	id: (name: string) => string,
): void {
	judgeJvm(evidence, data, loaded, id);
	judgePostgres(evidence, data, id);
	judgeSmall(evidence, data, id);
	judgeNode(evidence, data, allocatable, id);
	judgeOom(evidence, data, id);
}

/** The release Pods of one component. */
function of(data: Reduced, component: string): [string, Reduced["pods"][string]][] {
	return Object.entries(data.pods).filter(
		([name, pod]) => name.startsWith(`${cluster.namespace}/`) && pod.component === component,
	);
}

function judgeJvm(
	evidence: Evidence,
	data: Reduced,
	loaded: boolean,
	id: (name: string) => string,
): void {
	const note = loaded
		? ""
		: " (no load in the window: the 2× limit rule needs the bench load, so it is recorded only)";
	// B8a: the JVM roles. Peak ≤ 90 % of the limit; the limit ≤ 2 × the peak. The JVM sizes its heap from the limit, so a
	// limit keeps headroom above the peak (sizing.mdx § Memory headroom); above 2 × it needs a reason stated there.
	const heap = metricSeries(
		data.prometheus,
		"jvm_memory_used_bytes",
		(labels) => labels.area === "heap",
	);
	const gc = metricSeries(data.prometheus, "jvm_gc_pause_seconds_count");
	const jvm = [...of(data, "server"), ...of(data, "worker")].map(([name, pod]) => {
		const usage = data.usage[name];
		const short = name.split("/")[1] ?? name;
		const gcSeries = gc[short];
		// The cgroup's memory.current and memory.peak count reclaimable page cache. Judge the anon + kernel peak, as B8b
		// does for PostgreSQL; memory.peak is reported beside it, so the cache share stays visible.
		const split = data.split[short];
		return {
			name: short,
			unread: usage === undefined && split === undefined,
			peak: split?.anonKernelPeak ?? usage?.peakCurrent ?? 0,
			signal: split === undefined ? "memory.peak (no memory.stat split read)" : "anon + kernel",
			cgroupPeak: usage?.peakCurrent ?? null,
			limit: pod.limitMemory,
			env: pod.env,
			cpuP95: usage?.cpuP95 ?? null,
			heapMax: heap[short]?.max ?? null,
			gcPerMinute:
				gcSeries === undefined || gcSeries.spanMs === 0
					? null
					: ((gcSeries.last - gcSeries.first) * 60_000) / gcSeries.spanMs,
		};
	});
	const overNinety = jvm.filter((pod) => pod.limit > 0 && pod.peak > 0.9 * pod.limit);
	const oversized = jvm.filter((pod) => pod.peak > 0 && pod.limit > 2 * pod.peak);
	const jvmUnread = jvm.filter((pod) => pod.unread);
	evidence.check({
		id: id("B8a"),
		title:
			"server/worker: anon + kernel peak ≤ 90 % of the limit; limit ≤ 2 × that peak; heap, GC and CPU p95",
		status: decide(
			[
				[jvm.length === 0 || overNinety.length > 0, "fail"],
				[jvmUnread.length > 0, "not-run"],
				[oversized.length > 0 && loaded, "fail"],
				[oversized.length > 0, "recorded"],
			],
			"pass",
		),
		...(jvmUnread.length > 0 && overNinety.length === 0
			? { needs: `${UNREAD_NEEDS} (unread: ${jvmUnread.map((pod) => pod.name).join(", ")})` }
			: {}),
		rule: `${RULES} § B8a`,
		detail: `${jvm.map((pod) => `${pod.name}: ${pod.signal} peak ${pod.unread ? "unread" : mib(pod.peak)} / limit ${mib(pod.limit)}${pod.peak > 0 ? ` (limit ${(pod.limit / pod.peak).toFixed(2)} × peak)` : ""} (cgroup memory.peak with cache ${mib(pod.cgroupPeak)}), heap max ${mib(pod.heapMax)}, GC ${pod.gcPerMinute?.toFixed(1) ?? "n/a"}/min, CPU p95 ${pod.cpuP95?.toFixed(2) ?? "n/a"}`).join("; ")}${note}`,
		data: jvm,
	});
}

/** B8b: PostgreSQL. anon + kernel ≤ 80 % of the limit; peak connections ≤ 80 % of the pool sum. */
function judgePostgres(evidence: Evidence, data: Reduced, id: (name: string) => string): void {
	const postgres = of(data, "postgresql")[0];
	const pgSplit = postgres === undefined ? undefined : data.split[postgres[0].split("/")[1] ?? ""];
	let poolSum = 0;
	for (const entry of Object.values(metricSeries(data.prometheus, "hikaricp_connections_max"))) {
		poolSum += entry.max;
	}
	const connections = sqlMax(data.sql, "connections");
	const memoryOk =
		postgres === undefined || pgSplit === undefined
			? null
			: pgSplit.anonKernelPeak <= 0.8 * postgres[1].limitMemory;
	const connectionsOk =
		data.sql.length === 0 ? null : poolSum === 0 || connections <= 0.8 * poolSum;
	evidence.check({
		id: id("B8b"),
		title: "PostgreSQL: anon + kernel ≤ 80 % of the limit; connections ≤ 80 % of the pool sum",
		status: memoryOk === true && connectionsOk === true ? "pass" : "fail",
		rule: `${RULES} § B8b`,
		detail: `${memoryOk === null ? "memory: no PostgreSQL Pod or cgroup read" : `anon+kernel peak ${mib(pgSplit?.anonKernelPeak ?? null)} / limit ${mib(postgres?.[1].limitMemory ?? null)}`}; ${connectionsOk === null ? "connections: not read" : `connections peak ${connections} / pool sum ${poolSum} (max_connections ${sqlMax(data.sql, "max_connections")})`}`,
	});
}

/** B8d: webhook (JVM, ≤ 90 % of the limit), the controller and the image pre-pull DaemonSet (≤ request). */
function judgeSmall(evidence: Evidence, data: Reduced, id: (name: string) => string): void {
	const small = [
		...of(data, "webhook").map(([name, pod]) => ({ name, rule: "jvm" as const, pod })),
		...of(data, "agent-image").map(([name, pod]) => ({ name, rule: "request" as const, pod })),
		...Object.entries(data.pods)
			.filter(([name]) => name.startsWith(`${CONTROLLER}/`))
			.map(([name, pod]) => ({ name, rule: "request" as const, pod })),
	].map((entry) => {
		const unread = data.usage[entry.name] === undefined;
		const peak = data.usage[entry.name]?.peakCurrent ?? 0;
		const bound = entry.rule === "jvm" ? 0.9 * entry.pod.limitMemory : entry.pod.requestMemory;
		return { ...entry, peak, bound, unread, ok: bound > 0 && peak <= bound };
	});
	const smallUnread = small.filter((entry) => entry.unread);
	const smallFailed = small.length === 0 || small.some((entry) => !entry.unread && !entry.ok);
	evidence.check({
		id: id("B8d"),
		title: "webhook ≤ 90 % of its limit; controller and pre-pull Pods ≤ their request",
		status: decide(
			[
				[smallFailed, "fail"],
				[smallUnread.length > 0, "not-run"],
			],
			"pass",
		),
		...(!smallFailed && smallUnread.length > 0
			? {
					needs: `${UNREAD_NEEDS} (unread: ${smallUnread.map((entry) => entry.name.split("/")[1]).join(", ")})`,
				}
			: {}),
		rule: `${RULES} § B8d`,
		detail: small
			.map(
				(entry) =>
					`${entry.name.split("/")[1] ?? entry.name}: ${entry.unread ? "unread" : `${mib(entry.peak)} ≤ ${mib(entry.bound)} ${entry.ok ? "✓" : "✗"}`}`,
			)
			.join("; "),
	});
}

/** B8f: the node. */
function judgeNode(
	evidence: Evidence,
	data: Reduced,
	allocatable: Allocatable[],
	id: (name: string) => string,
): void {
	const requests: Record<string, number> = {};
	for (const pod of Object.values(data.podsLast)) {
		if (pod.phase === "Running") {
			requests[pod.node] = (requests[pod.node] ?? 0) + pod.requestMemory;
		}
	}
	const swapIn = Object.values(data.nodes).reduce((sum, node) => sum + node.swapIn, 0);
	const psi = Math.max(0, ...Object.values(data.nodes).map((node) => node.memSome10Max));
	// Swap is a host prerequisite (sizing.mdx § Profiles at a glance), so swap-in is reported, not judged.
	const nodeOk =
		psi < 10 &&
		allocatable.every((entry) => entry.allocatable - (requests[entry.node] ?? 0) >= 1024 ** 3);
	evidence.check({
		id: id("B8f"),
		title:
			"node: requests fit allocatable with ≥ 1 GiB; memory PSI some avg10 < 10 %; swap-in recorded",
		// A k3d node is a container in the Docker VM: its PSI and swap counters are the VM's, shared with every other
		// container, so a k3d run records them and judges nothing by them.
		status: decide(
			[
				[cluster.target === "k3d", "recorded"],
				[nodeOk, "pass"],
			],
			"fail",
		),
		rule: `${RULES} § B8f (the release's requests per node; other tenants' requests are outside this sum)`,
		detail: `${allocatable.map((entry) => `${entry.node}: release requests ${mib(requests[entry.node] ?? 0)} of ${mib(entry.allocatable)} allocatable`).join("; ")}; swap-in ${swapIn} pages; memory PSI max ${psi.toFixed(2)} %${cluster.target === "k3d" ? " (k3d: PSI and swap are the Docker VM's)" : ""}`,
		data: data.nodes,
	});
}

/** No non-sandbox Pod OOM-killed or evicted in the window. */
function judgeOom(evidence: Evidence, data: Reduced, id: (name: string) => string): void {
	const victims = Object.entries(data.pods).filter(
		([name, pod]) =>
			!name.startsWith(`${cluster.sandboxes}/`) &&
			(pod.reason === "Evicted" ||
				(pod.restarts > pod.firstRestarts && pod.lastTerminated.includes("OOMKilled"))),
	);
	const ooms = Object.entries(data.split).filter(([, split]) => split.oomKills > 0);
	evidence.check({
		id: id("B8-oom"),
		title: "zero OOM kills and evictions of non-sandbox Pods",
		status: victims.length === 0 && ooms.length === 0 ? "pass" : "fail",
		rule: `${RULES} § B8-oom`,
		detail:
			victims.length === 0 && ooms.length === 0
				? "none"
				: [
						...victims.map(([name, pod]) => `${name}: ${pod.reason || pod.lastTerminated}`),
						...ooms.map(([name, split]) => `${name}: oom_kill +${split.oomKills}`),
					].join("; "),
	});
}

interface Allocatable {
	node: string;
	allocatable: number;
}

async function readNodes(evidence: Evidence): Promise<Allocatable[]> {
	const allocatable = items(await cluster.json(["get", "nodes"])).map((node) => ({
		node: str(path2(node, ["metadata", "name"])),
		allocatable: quantity(str(path2(node, ["status", "allocatable", "memory"]))),
	}));
	evidence.raw("nodes.json", allocatable);
	return allocatable;
}

/** B8-tier: the chart's OOM and eviction order, from the repository's memory drill on a disposable k3d node. */
async function memoryTier(evidence: Evidence): Promise<void> {
	const title = "the chart's OOM and eviction order on a full node";
	if (cluster.target !== "k3d") {
		evidence.notRun(
			"B8-tier",
			title,
			"a disposable k3d node: CI runs scripts/kubernetes-memory-drill.ts (ci-kubernetes.yml)",
		);
		return;
	}
	if (!values["memory-drill"] || values.images === undefined) {
		evidence.notRun(
			"B8-tier",
			title,
			"--memory-drill --images <release-images.tsv> on the k3d target",
		);
		return;
	}
	// The drill accepts only a disposable cluster: CI=true and the kubeconfig of k3d-<cluster>.
	process.env.CI = "true";
	process.env.KUBECONFIG = cluster.options.kubeconfig;
	try {
		await memoryDrill(values.images, cluster.options.k3dCluster);
		evidence.check({
			id: "B8-tier",
			title,
			status: "pass",
			rule: `${RULES} § B8-tier`,
			detail:
				"scripts/kubernetes-memory-drill.ts passed: the eviction order holds on a disposable node",
		});
	} catch (error) {
		evidence.check({
			id: "B8-tier",
			title,
			status: "fail",
			rule: `${RULES} § B8-tier`,
			detail: error instanceof Error ? (error.message.split("\n")[0] ?? "") : String(error),
		});
	}
}

async function bench(evidence: Evidence): Promise<void> {
	const allocatable = await readNodes(evidence);
	const rounds = Math.max(1, Number(values.rounds));
	const minutes = Number(values.minutes);
	const gapMinutes = Number(values["round-gap-minutes"]);
	const reads = await readsBefore(cluster);
	const span = (rounds * minutes + (rounds - 1) * gapMinutes) * 60_000;
	const probe = probeOverview(
		{ url: values["overview-url"], tokenFile: values["token-file"] },
		Date.now() + span,
	);
	for (let round = 1; round <= rounds; round += 1) {
		const window = await measure(evidence, `round${round}`, fixture?.reviews.length ?? 0, minutes);
		judgeWindow(
			evidence,
			window,
			await reduce(evidence, window),
			allocatable,
			rounds > 1 ? `-r${round}` : "",
		);
		if (round < rounds) {
			await sleep(gapMinutes * 60_000);
		}
	}
	await judgeReads(cluster, evidence, reads, await probe, named);
	await memoryTier(evidence);
}

const evidence = new Evidence(common.out, `bench-${common.target}`);
const description = await cluster.describe();
evidence.raw("cluster.json", description);
const notes = [
	`Collectors: Pod cgroups and node PSI/swap every ${intervalMs / 1000} s, Prometheus and SQL every 15 s, memory split every 30 s.`,
	...(fixture !== null && fixture.reviews.length < 4
		? [
				`The fixture names ${fixture.reviews.length} pull requests; the bench load is at least 4 reviews at once.`,
			]
		: []),
];
try {
	await bench(evidence);
} catch (error) {
	evidence.check({
		id: "harness",
		title: "the bench harness completed",
		status: "fail",
		detail: error instanceof Error ? error.message : String(error),
	});
}
const verdict = evidence.finish(
	{ command: common.command, commit: await commitOf(), cluster: description },
	notes,
);
process.exitCode = verdict === "FAIL" ? 1 : 0;
