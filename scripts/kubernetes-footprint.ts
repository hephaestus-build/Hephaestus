/** Read-only node and container sampler. Run locally; remote-exec is the only remote transport. */
import { readFile, writeFile } from "node:fs/promises";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
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
	},
});
if (values.host !== "hephaestus-staging")
	throw new Error("This sampler is restricted to hephaestus-staging");
const count = positiveInteger(values.samples, "--samples");
const interval = positiveInteger(values["interval-ms"], "--interval-ms");
const remote = (command: string) => output("remote-exec", [values.host, command]);
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
const counters = `sudo sh -c 'printf "NODE\\n"; cat /proc/meminfo; for f in $(find /sys/fs/cgroup -name memory.stat -path "*cri-containerd*"); do d="\${f%/memory.stat}"; printf "CGROUP %s\\n" "$d"; for k in memory.current memory.peak memory.stat; do printf "%s\\n" "$k"; cat "$d/$k" 2>/dev/null || printf "unavailable\\n"; done; done'`;
const snapshots: {
	t: number;
	nodeUsed: number;
	nodeAvailable: number;
	containers: Record<string, Record<string, number | null>>;
}[] = [];
const identities: Record<
	string,
	{
		namespace: string;
		pod: string;
		container: string;
		image: unknown;
		resources: unknown;
		mounts: unknown;
		volumes: unknown;
	}
> = {};
for (let index = 0; index < count; index++) {
	const started = Date.now();
	const pods = asRecord(
		parseJson(
			await remote("sudo kubectl get pods -A --field-selector=status.phase=Running -o json"),
		),
		"pods",
	);
	for (const value of asArray(pods.items, "pods.items")) {
		const pod = asRecord(value, "pod");
		const metadata = asRecord(pod.metadata, "metadata");
		const spec = asRecord(pod.spec, "spec");
		const statuses = asRecord(pod.status, "status").containerStatuses;
		for (const value of statuses === undefined ? [] : asArray(statuses, "containerStatuses")) {
			const status = asRecord(value, "containerStatus");
			if (typeof status.containerID !== "string") continue;
			const id = status.containerID.replace("containerd://", "");
			const container = asArray(spec.containers, "containers")
				.map((value) => asRecord(value, "container"))
				.find((value) => value.name === status.name);
			identities[id] = {
				namespace: asString(metadata.namespace, "namespace"),
				pod: asString(metadata.name, "pod.name"),
				container: asString(status.name, "container.name"),
				image: container?.image,
				resources: container?.resources,
				mounts: container?.volumeMounts,
				// Keep emptyDir metadata only. Never collect projected credentials or environment values.
				volumes: asArray(spec.volumes ?? [], "volumes")
					.map((value) => asRecord(value, "volume"))
					.filter((value) => value.emptyDir !== undefined),
			};
		}
	}
	const raw = await remote(counters);
	const meminfo = raw.split("CGROUP ")[0] ?? "";
	const mem = (name: string) => {
		const match = new RegExp(`^${name}:\\s+(\\d+) kB$`, "m").exec(meminfo);
		if (!match) throw new Error(`Missing node counter ${name}`);
		return Number(match[1]) * 1024;
	};
	const containers: Record<string, Record<string, number | null>> = {};
	for (const block of raw.split("CGROUP ").slice(1)) {
		const id = /cri-containerd-([a-f0-9]{64})\.scope/u.exec(block)?.[1];
		if (!id) continue;
		const counter = (key: string) => {
			const match = new RegExp(`^${key}(?:\\n| )(\\d+)$`, "m").exec(block);
			return match ? Number(match[1]) : null;
		};
		containers[id] = {
			current: counter("memory.current"),
			lifetimePeak: counter("memory.peak"),
			anon: counter("anon"),
			file: counter("file"),
			shmem: counter("shmem"),
			kernel: counter("kernel"),
			kernelAccounted: ["kernel_stack", "pagetables", "percpu", "sock", "slab"].reduce<
				number | null
			>((sum, key) => {
				const value = counter(key);
				return sum === null || value === null ? null : sum + value;
			}, 0),
		};
	}
	snapshots.push({
		t: started,
		nodeUsed: mem("MemTotal") - mem("MemAvailable"),
		nodeAvailable: mem("MemAvailable"),
		containers,
	});
	if (index + 1 < count) await sleep(Math.max(0, interval - (Date.now() - started)));
}
const observations =
	values.timeline === undefined
		? []
		: asArray(parseJson(await readFile(values.timeline, "utf8")), "timeline").map(
				parseQueueObservation,
			);
const bytes = (values: readonly number[]) => {
	const result = distribution(values);
	return {
		count: result.count,
		p50Bytes: result.p50Ms,
		p95Bytes: result.p95Ms,
		maxBytes: result.maxMs,
	};
};
const report = {
	startedAt: new Date(snapshots[0]?.t ?? Date.now()).toISOString(),
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
					["current", "lifetimePeak", "anon", "file", "shmem", "kernel", "kernelAccounted"].map(
						(key) => [
							key,
							bytes(
								snapshots.flatMap((row) => {
									const value = row.containers[id]?.[key];
									return value == null ? [] : [value];
								}),
							),
						],
					),
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
