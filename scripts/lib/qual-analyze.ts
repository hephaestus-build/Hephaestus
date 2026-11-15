/** Reads a bench window's raw JSON lines back and reduces them to the numbers that B1–B8 judge. */
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { percentile } from "./qual-args.ts";
import { arr, json, num, path2, rec, str } from "./qual-cluster.ts";
import type { PodSeen } from "./qual-collect.ts";
import type { Sample } from "./qual-state.ts";

export interface Line {
	at: number;
	sample: unknown;
}

export function readLines(
	dir: string,
	name: string,
	from = 0,
	to = Number.POSITIVE_INFINITY,
): Line[] {
	const file = path.join(dir, "raw", name);
	if (!existsSync(file)) {
		return [];
	}
	return readFileSync(file, "utf8")
		.split("\n")
		.filter(Boolean)
		.map((line) => json(line))
		.map((entry) => ({
			at: Date.parse(str(path2(entry, ["at"]))),
			sample: path2(entry, ["sample"]),
		}))
		.filter((entry) => entry.sample !== undefined && entry.at >= from && entry.at <= to);
}

export interface PodUsage {
	peakCurrent: number;
	peakLifetime: number;
	cpuP95: number | null;
	cpuSamples: number;
}

/** Per Pod: the highest memory.current in the window, memory.peak, and the CPU p95 in cores. */
export function podUsage(lines: Line[]): Record<string, PodUsage> {
	const usage: Record<string, PodUsage> = {};
	const cpu: Record<string, number[]> = {};
	const last: Record<string, { at: number; usec: number }> = {};
	for (const line of lines) {
		for (const [pod, value] of Object.entries(rec(line.sample))) {
			const mem = num(path2(value, ["memBytes"]));
			const peak = num(path2(value, ["peakBytes"]));
			const usec = num(path2(value, ["cpuUsec"]));
			const entry = entryOf(usage, pod, () => ({
				peakCurrent: 0,
				peakLifetime: 0,
				cpuP95: null,
				cpuSamples: 0,
			}));
			entry.peakCurrent = Math.max(entry.peakCurrent, Number.isFinite(mem) ? mem : 0);
			entry.peakLifetime = Math.max(entry.peakLifetime, Number.isFinite(peak) ? peak : 0);
			const previous = last[pod];
			if (
				previous !== undefined &&
				Number.isFinite(usec) &&
				usec >= previous.usec &&
				line.at > previous.at
			) {
				(cpu[pod] ??= []).push((usec - previous.usec) / 1000 / (line.at - previous.at));
			}
			if (Number.isFinite(usec)) {
				last[pod] = { at: line.at, usec };
			}
		}
	}
	for (const [pod, values] of Object.entries(cpu)) {
		const entry = usage[pod];
		if (entry !== undefined) {
			entry.cpuP95 = percentile(values, 95);
			entry.cpuSamples = values.length;
		}
	}
	return usage;
}

/** The union of every Pod seen in the window, with its last state and its highest restart count. */
export function podsSeen(lines: Line[]): Record<string, PodSeen & { firstRestarts: number }> {
	const seen: Record<string, PodSeen & { firstRestarts: number }> = {};
	for (const line of lines) {
		for (const [name, value] of Object.entries(rec(line.sample))) {
			const pod: PodSeen = {
				component: str(path2(value, ["component"])),
				sandboxKind: str(path2(value, ["sandboxKind"])),
				uid: str(path2(value, ["uid"])),
				node: str(path2(value, ["node"])),
				phase: str(path2(value, ["phase"])),
				reason: str(path2(value, ["reason"])),
				restarts: num(path2(value, ["restarts"])),
				lastTerminated: str(path2(value, ["lastTerminated"])),
				requestMemory: num(path2(value, ["requestMemory"])),
				limitMemory: num(path2(value, ["limitMemory"])),
				requestCpu: num(path2(value, ["requestCpu"])),
				limitCpu: num(path2(value, ["limitCpu"])),
				env: Object.fromEntries(
					Object.entries(rec(path2(value, ["env"]))).map(([key, entry]) => [key, str(entry)]),
				),
			};
			seen[name] = { ...pod, firstRestarts: seen[name]?.firstRestarts ?? pod.restarts };
		}
	}
	return seen;
}

/** Per Pod: the highest anon + kernel bytes (the charge without reclaimable file cache) and new OOM kills. */
export function memorySplit(
	lines: Line[],
): Record<string, { anonKernelPeak: number; oomKills: number }> {
	const split: Record<string, { anonKernelPeak: number; oomKills: number; first: number }> = {};
	for (const line of lines) {
		for (const [pod, value] of Object.entries(rec(line.sample))) {
			const anon = Number(str(path2(value, ["anon"])));
			const kernel = Number(str(path2(value, ["kernel"])));
			const oom = Number(str(path2(value, ["oom_kill"])));
			const entry = entryOf(split, pod, () => ({
				anonKernelPeak: 0,
				oomKills: 0,
				first: Number.isFinite(oom) ? oom : 0,
			}));
			if (Number.isFinite(anon) && Number.isFinite(kernel)) {
				entry.anonKernelPeak = Math.max(entry.anonKernelPeak, anon + kernel);
			}
			if (Number.isFinite(oom)) {
				entry.oomKills = oom - entry.first;
			}
		}
	}
	return Object.fromEntries(
		Object.entries(split).map(([pod, { anonKernelPeak, oomKills }]) => [
			pod,
			{ anonKernelPeak, oomKills },
		]),
	);
}

export interface NodeSummary {
	memSome10Max: number;
	cpuSome10Max: number;
	swapIn: number;
	memAvailableMin: number;
	swapTotal: number;
}

export function nodeSummary(lines: Line[]): Record<string, NodeSummary> {
	const summary: Record<string, NodeSummary & { firstSwapIn: number }> = {};
	for (const line of lines) {
		for (const [node, value] of Object.entries(rec(line.sample))) {
			const swapIn = num(path2(value, ["pswpin"]));
			const entry = entryOf(summary, node, () => ({
				memSome10Max: 0,
				cpuSome10Max: 0,
				swapIn: 0,
				memAvailableMin: Number.POSITIVE_INFINITY,
				swapTotal: 0,
				firstSwapIn: swapIn,
			}));
			entry.memSome10Max = Math.max(entry.memSome10Max, num(path2(value, ["mem_some10"])) || 0);
			entry.cpuSome10Max = Math.max(entry.cpuSome10Max, num(path2(value, ["cpu_some10"])) || 0);
			entry.memAvailableMin = Math.min(
				entry.memAvailableMin,
				num(path2(value, ["MemAvailable"])) || Number.POSITIVE_INFINITY,
			);
			entry.swapTotal = num(path2(value, ["SwapTotal"])) || 0;
			if (Number.isFinite(swapIn)) {
				entry.swapIn = swapIn - entry.firstSwapIn;
			}
		}
	}
	return Object.fromEntries(
		Object.entries(summary).map(([node, { firstSwapIn: _first, ...rest }]) => [node, rest]),
	);
}

function samplesOf(value: unknown): Sample[] {
	return arr(value).map((entry) => ({
		name: str(path2(entry, ["name"])),
		labels: Object.fromEntries(
			Object.entries(rec(path2(entry, ["labels"]))).map(([key, label]) => [key, str(label)]),
		),
		value: num(path2(entry, ["value"])),
	}));
}

/** One metric's per-Pod values over the window: the first and last sum, and the highest. */
export function metricSeries(
	lines: Line[],
	name: string,
	match: (labels: Record<string, string>) => boolean = () => true,
): Record<string, { first: number; last: number; max: number; spanMs: number }> {
	const series: Record<
		string,
		{ first: number; last: number; max: number; firstAt: number; lastAt: number }
	> = {};
	for (const line of lines) {
		for (const [pod, value] of Object.entries(rec(line.sample))) {
			if (!Array.isArray(value)) {
				continue;
			}
			const total = samplesOf(value)
				.filter((sample) => sample.name === name && match(sample.labels))
				.reduce((sum, sample) => sum + sample.value, 0);
			const entry = entryOf(series, pod, () => ({
				first: total,
				last: total,
				max: total,
				firstAt: line.at,
				lastAt: line.at,
			}));
			entry.last = total;
			entry.lastAt = line.at;
			entry.max = Math.max(entry.max, total);
		}
	}
	return Object.fromEntries(
		Object.entries(series).map(([pod, entry]) => [
			pod,
			{
				first: entry.first,
				last: entry.last,
				max: entry.max,
				spanMs: entry.lastAt - entry.firstAt,
			},
		]),
	);
}

/** One Pod's cumulative bucket counts of a histogram in one scrape, by upper bound. */
function bucketsOf(
	value: unknown,
	name: string,
	match: (labels: Record<string, string>) => boolean,
): Map<string, number> {
	const buckets = new Map<string, number>();
	if (!Array.isArray(value)) {
		return buckets;
	}
	for (const sample of samplesOf(value)) {
		if (sample.name === `${name}_bucket` && match(sample.labels)) {
			const le = sample.labels.le ?? "+Inf";
			buckets.set(le, (buckets.get(le) ?? 0) + sample.value);
		}
	}
	return buckets;
}

/** A quantile of a Prometheus histogram over the window, from the bucket deltas summed over Pods. */
export function histogramQuantile(
	lines: Line[],
	name: string,
	q: number,
	match: (labels: Record<string, string>) => boolean = () => true,
): { value: number | null; count: number } {
	const first = new Map<string, number>();
	const last = new Map<string, number>();
	for (const line of lines) {
		for (const [pod, value] of Object.entries(rec(line.sample))) {
			for (const [le, count] of bucketsOf(value, name, match)) {
				const key = `${pod}|${le}`;
				if (!first.has(key)) {
					first.set(key, count);
				}
				last.set(key, count);
			}
		}
	}
	const byLe = new Map<number, number>();
	for (const [key, count] of last) {
		const le = key.split("|")[1] ?? "+Inf";
		const bound = le === "+Inf" ? Number.POSITIVE_INFINITY : Number(le);
		byLe.set(bound, (byLe.get(bound) ?? 0) + count - (first.get(key) ?? 0));
	}
	const sorted = [...byLe.entries()].toSorted((a, b) => a[0] - b[0]);
	const total = sorted.at(-1)?.[1] ?? 0;
	if (total <= 0) {
		return { value: null, count: 0 };
	}
	const rank = q * total;
	const hit = sorted.find(([, count]) => count >= rank);
	return { value: hit === undefined ? null : hit[0], count: total };
}

/** The highest value of one field over the SQL samples. */
export function sqlMax(lines: Line[], field: string): number {
	let max = 0;
	for (const line of lines) {
		max = Math.max(max, num(path2(line.sample, ["database", field])) || 0);
	}
	return max;
}

/** The record's entry for `key`, created by `make` on first use. */
function entryOf<T>(record: Record<string, T>, key: string, make: () => T): T {
	const existing = record[key];
	if (existing !== undefined) {
		return existing;
	}
	const created = make();
	record[key] = created;
	return created;
}
