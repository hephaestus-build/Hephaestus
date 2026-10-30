/** Read-only node and container sampler. Run locally; remote-exec is the only remote transport. */
import { appendFile, readFile, writeFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { asArray, asRecord, parseJson } from "./lib/json.ts";
import { parseContainerIdentities } from "./lib/kubernetes-footprint.ts";
import { output } from "./lib/process.ts";
import {
	distribution,
	parseQueueObservation,
	positiveInteger,
	queueReport,
} from "./lib/sandbox-footprint-report.ts";

const { values } = parseArgs({
	options: {
		host: { type: "string", default: "hephaestus-staging" },
		samples: { type: "string", default: "60" },
		"interval-ms": { type: "string", default: "1000" },
		timeline: { type: "string" },
		out: { type: "string", default: "kubernetes-footprint.json" },
		"provider-budget": { type: "string" },
		"minimum-available-mib": { type: "string" },
		"bench-postgres": { type: "boolean" },
	},
});
if (values.host !== "hephaestus-staging") {
	throw new Error("This sampler is restricted to hephaestus-staging");
}
const count = positiveInteger(values.samples, "--samples");
const interval = positiveInteger(values["interval-ms"], "--interval-ms");
const minimumAvailable =
	values["minimum-available-mib"] === undefined
		? null
		: positiveInteger(values["minimum-available-mib"], "--minimum-available-mib") * 1024 * 1024;
const state = { stopping: false };
let safetyStop = false;
function stop() {
	state.stopping = true;
}
for (const signal of ["SIGTERM", "SIGINT"] as const) {
	process.once(signal, stop);
}
const remote = async (command: string) => output("remote-exec", [values.host, command]);
const nodes = asRecord(parseJson(await remote("sudo kubectl get nodes -o json")), "nodes");
const nodeBudgets = asArray(nodes.items, "nodes.items").map((value) => {
	const node = asRecord(value, "node");
	const status = asRecord(node.status, "node.status");
	return {
		name: asRecord(node.metadata, "metadata").name,
		capacity: status.capacity,
		allocatable: status.allocatable,
		nodeInfo: status.nodeInfo,
	};
});
// POSIX shell at the remote runtime boundary: the host need not have Node. Reads counters only.
const counters = `sudo sh -c 'printf "NODE\\n"; cat /proc/meminfo; head -1 /proc/stat; for k in cpu memory io; do printf "PRESSURE %s\\n" "$k"; cat "/proc/pressure/$k" 2>/dev/null || printf "unavailable\\n"; done; for f in $(find /sys/fs/cgroup -name memory.stat -path "*cri-containerd*"); do d="\${f%/memory.stat}"; printf "CGROUP %s\\n" "$d"; for k in memory.current memory.peak memory.stat memory.events cpu.stat; do printf "%s\\n" "$k"; cat "$d/$k" 2>/dev/null || printf "unavailable\\n"; done; printf "processRss\\n"; for p in $(cat "$d/cgroup.procs" 2>/dev/null); do awk "/^VmRSS:/ {print \\$2}" "/proc/$p/status" 2>/dev/null; done | awk "{total+=\\$1} END {printf \\"%.0f\\\\n\\",total*1024}"; done'`;
const snapshots: {
	t: number;
	nodeUsed: number;
	nodeAvailable: number;
	nodeCounters: {
		fileCache: number;
		swapUsed: number;
		cpu: number[];
		pressure: Record<string, string>;
	};
	containers: Record<string, Record<string, number | null>>;
	postgres: unknown;
}[] = [];
const identities: ReturnType<typeof parseContainerIdentities> = {};
await writeFile(`${values.out}.jsonl`, "", { flag: "wx" });
for (let index = 0; index < count; index += 1) {
	const started = Date.now();
	Object.assign(
		identities,
		parseContainerIdentities(parseJson(await remote("sudo kubectl get pods -A -o json"))),
	);
	const raw = await remote(counters);
	const meminfo = raw.split("CGROUP ")[0] ?? "";
	const mem = (name: string) => {
		const match = new RegExp(`^${name}:\\s+(\\d+) kB$`, "mu").exec(meminfo);
		if (!match) {
			throw new Error(`Missing node counter ${name}`);
		}
		return Number(match[1]) * 1024;
	};
	const containers: Record<string, Record<string, number | null>> = {};
	for (const block of raw.split("CGROUP ").slice(1)) {
		const id = /cri-containerd-(?<id>[a-f0-9]{64})\.scope/u.exec(block)?.groups?.id;
		if (id === undefined) {
			continue;
		}
		const counter = (key: string) => {
			const match = new RegExp(
				`^${key.replaceAll(".", String.raw`\.`)}(?:\\n| )(\\d+)$`,
				"mu",
			).exec(block);
			return match ? Number(match[1]) : null;
		};
		let kernelAccounted: number | null = 0;
		for (const key of ["kernel_stack", "pagetables", "percpu", "sock", "slab"]) {
			const value = counter(key);
			if (value === null) {
				kernelAccounted = null;
				break;
			}
			kernelAccounted += value;
		}
		containers[id] = {
			current: counter("memory.current"),
			lifetimePeak: counter("memory.peak"),
			anon: counter("anon"),
			file: counter("file"),
			shmem: counter("shmem"),
			kernel: counter("kernel"),
			kernelAccounted,
			processRss: counter("processRss"),
			cpuUsageUsec: counter("usage_usec"),
			cpuThrottledUsec: counter("throttled_usec"),
			cpuThrottledPeriods: counter("nr_throttled"),
			oom: counter("oom"),
			oomKills: counter("oom_kill"),
		};
	}
	let postgres: unknown = null;
	if (values["bench-postgres"] === true) {
		const rawPostgres = await remote(
			`sudo -n kubectl exec -n capacity-bench-20261030 bench-postgresql-0 -- sh -c 'exec psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -At -c "SELECT json_build_object($$connections$$,count(*),$$active$$,count(*) FILTER(WHERE state=$$active$$),$$lockWaits$$,count(*) FILTER(WHERE wait_event_type=$$Lock$$)) FROM pg_stat_activity WHERE datname=current_database();"'`.replaceAll(
				"$$",
				String.raw`\$\$`,
			),
		);
		postgres = parseJson(rawPostgres.trim());
	}
	const snapshot = {
		t: started,
		nodeUsed: mem("MemTotal") - mem("MemAvailable"),
		nodeAvailable: mem("MemAvailable"),
		nodeCounters: {
			fileCache: mem("Cached"),
			swapUsed: mem("SwapTotal") - mem("SwapFree"),
			cpu: (/^cpu\s+(?<values>[\d ]+)$/mu.exec(meminfo)?.groups?.values ?? "")
				.trim()
				.split(/\s+/u)
				.filter(Boolean)
				.map(Number),
			pressure: Object.fromEntries(
				["cpu", "memory", "io"].map((kind) => [
					kind,
					meminfo.split(`PRESSURE ${kind}\n`)[1]?.split("PRESSURE ")[0]?.trim() ?? "unavailable",
				]),
			),
		},
		containers,
		postgres,
	};
	snapshots.push(snapshot);
	await appendFile(`${values.out}.jsonl`, `${JSON.stringify(snapshot)}\n`);
	await writeFile(`${values.out}.identities.json`, JSON.stringify(identities));
	if (index === 0) {
		process.stdout.write("SAMPLER_READY\n");
	}
	if (minimumAvailable !== null && snapshot.nodeAvailable < minimumAvailable) {
		safetyStop = true;
		process.stdout.write(`HEADROOM_STOP ${snapshot.nodeAvailable}\n`);
		break;
	}
	if (state.stopping) {
		break;
	}
	if (index + 1 < count) {
		await sleep(Math.max(0, interval - (Date.now() - started)));
	}
}
const observations =
	values.timeline === undefined
		? []
		: asArray(parseJson(await readFile(values.timeline, "utf8")), "timeline").map(
				parseQueueObservation,
			);
const bytes = (measurements: readonly number[]) => {
	const result = distribution(measurements);
	return {
		count: result.count,
		p50Bytes: result.p50,
		p95Bytes: result.p95,
		maxBytes: result.max,
	};
};
const report = {
	startedAt: new Date(snapshots[0]?.t ?? Date.now()).toISOString(),
	safetyStop,
	measurement:
		"read-only Kubernetes cgroup v2; node use = MemTotal - MemAvailable; no load generated",
	nodeBudgets,
	providerBudget: values["provider-budget"] ?? null,
	intervalMs: interval,
	samples: snapshots.length,
	nodeUsed: bytes(snapshots.map((row) => row.nodeUsed)),
	minimumNodeAvailableBytes: Math.min(...snapshots.map((row) => row.nodeAvailable)),
	nodePeakBytes: Math.max(...snapshots.map((row) => row.nodeUsed)),
	containers: Object.fromEntries(
		Object.entries(identities).map(([id, identity]) => [
			id,
			{
				...identity,
				counters: Object.fromEntries(
					[
						"current",
						"lifetimePeak",
						"anon",
						"file",
						"shmem",
						"kernel",
						"kernelAccounted",
						"processRss",
					].map((key) => [
						key,
						bytes(
							snapshots.flatMap((row) => {
								const value = row.containers[id]?.[key];
								return value == null ? [] : [value];
							}),
						),
					]),
				),
			},
		]),
	),
	queues: queueReport(observations),
	snapshots,
};
await writeFile(values.out, `${JSON.stringify(report, null, 2)}\n`);
process.stdout.write(
	`${values.out}: ${snapshots.length} samples; node peak ${report.nodePeakBytes} bytes; ${observations.length} queue observations\n`,
);
