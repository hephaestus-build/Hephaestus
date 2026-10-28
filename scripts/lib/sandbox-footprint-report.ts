/**
 * The pure parts of the sandbox footprint benchmark (`scripts/sandbox-footprint.ts`): what a sampler
 * line must hold, how the report bounds its maps, and when a run counts as a measurement.
 */
import { isRecord, parseJson } from "./json.ts";
import { percentile } from "./statistics.ts";

/** How often the sampler reads each sandbox's cgroup. */
export const SAMPLE_MS = 250;

/** A runtime with fewer samples ran too briefly to measure: 2 s at the sampler's interval. */
export const RUNTIME_SAMPLE_FLOOR = 2000 / SAMPLE_MS;

/** The exit code that the benchmark records for a runtime that did not start. */
export const NOT_STARTED = -1;

/** The most entries that one data-keyed map of the report keeps. */
export const REPORT_MAP_LIMIT = 10;

export const BUDGETS =
	"not enforced: controlled fixture memory and live service capacity require separate qualification";

export interface Sample {
	t: number;
	label: string;
	current: number;
	peak: number;
	anon: number;
	file: number;
	shmem: number;
	kernel: number;
	cpuUsec: number;
	throttledUsec: number;
	pids: number;
	procs: {
		pid: number;
		cmd: string;
		rss: number;
		hwm: number;
		anon: number;
		file: number;
		shmem: number;
	}[];
	tmpfs?: Record<string, number>;
}

const SAMPLE_NUMBERS = [
	"t",
	"current",
	"peak",
	"anon",
	"file",
	"shmem",
	"kernel",
	"cpuUsec",
	"throttledUsec",
	"pids",
] as const;
const PROCESS_NUMBERS = ["pid", "rss", "hwm", "anon", "file", "shmem"] as const;

export function isSample(value: unknown): value is Sample {
	return (
		isRecord(value) &&
		typeof value.label === "string" &&
		SAMPLE_NUMBERS.every((key) => nonnegative(value[key])) &&
		Array.isArray(value.procs) &&
		value.procs.every(
			(proc) =>
				isRecord(proc) &&
				typeof proc.cmd === "string" &&
				PROCESS_NUMBERS.every((key) => nonnegative(proc[key])),
		) &&
		(value.tmpfs === undefined ||
			(isRecord(value.tmpfs) && Object.values(value.tmpfs).every(nonnegative)))
	);
}

function nonnegative(value: unknown): value is number {
	return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

/** An attempt's monotonic phase timestamps, in milliseconds from one clock. No message or prompt text. */
export interface QueueObservation {
	id: string;
	kind: "review" | "composition" | "mentor";
	submitted: number;
	eligible: number;
	granted: number;
	podReady?: number;
	firstEvent?: number;
}

function optionalPhase(
	value: unknown,
	floor: number,
	ceiling = Number.POSITIVE_INFINITY,
): value is number | undefined {
	return value === undefined || (nonnegative(value) && value >= floor && value <= ceiling);
}

export function parseQueueObservation(value: unknown): QueueObservation {
	if (
		!isRecord(value) ||
		typeof value.id !== "string" ||
		(value.kind !== "review" && value.kind !== "composition" && value.kind !== "mentor") ||
		!nonnegative(value.submitted) ||
		!nonnegative(value.eligible) ||
		!nonnegative(value.granted) ||
		value.submitted > value.eligible ||
		value.eligible > value.granted ||
		!optionalPhase(value.podReady, value.eligible, value.granted) ||
		!optionalPhase(value.firstEvent, value.granted) ||
		(value.kind === "review" && value.podReady === undefined)
	) {
		throw new Error("Invalid queue observation: require ordered phases and review Pod readiness");
	}
	return {
		id: value.id,
		kind: value.kind,
		submitted: value.submitted,
		eligible: value.eligible,
		granted: value.granted,
		...(value.podReady === undefined ? {} : { podReady: value.podReady }),
		...(value.firstEvent === undefined ? {} : { firstEvent: value.firstEvent }),
	};
}

/** Missing data stays null. The sample count is part of every percentile claim. */
export function distribution(values: readonly number[]) {
	return {
		count: values.length,
		p50: percentile(values, 0.5),
		p95: percentile(values, 0.95),
		max: values.length > 0 ? Math.max(...values) : null,
	};
}

function milliseconds(values: readonly number[]) {
	const result = distribution(values);
	return { count: result.count, p50Ms: result.p50, p95Ms: result.p95, maxMs: result.max };
}

export function queueReport(observations: readonly QueueObservation[]) {
	const summarize = (kind: QueueObservation["kind"]) => {
		const rows = observations.filter((row) => row.kind === kind);
		return {
			capacity: milliseconds(rows.map((row) => row.granted - row.eligible)),
			intentionalConversationWait: milliseconds(
				(kind === "mentor" ? rows : []).map((row) => row.eligible - row.submitted),
			),
			readinessWait: milliseconds(
				(kind === "mentor" ? [] : rows).map((row) => row.eligible - row.submitted),
			),
			podReady: milliseconds(
				rows.flatMap((row) => (row.podReady === undefined ? [] : [row.podReady - row.eligible])),
			),
			modelAfterPodReady: milliseconds(
				rows.flatMap((row) => (row.podReady === undefined ? [] : [row.granted - row.podReady])),
			),
			upstreamFirstEvent: milliseconds(
				rows.flatMap((row) => (row.firstEvent === undefined ? [] : [row.firstEvent - row.granted])),
			),
			endToEnd: milliseconds(rows.map((row) => row.granted - row.submitted)),
			sufficientForObjectives: rows.length >= 200,
		};
	};
	return {
		review: summarize("review"),
		composition: summarize("composition"),
		mentor: summarize("mentor"),
	};
}

/** One sampler line as a sample, or `null` for a line that is not JSON or not a sample. */
export function parseSample(line: string): Sample | null {
	let value: unknown;
	try {
		value = parseJson(line);
	} catch {
		return null;
	}
	return isSample(value) ? value : null;
}

/** The `limit` entries with the largest `size`, largest first. */
export function largest<T>(entries: readonly T[], size: (entry: T) => number, limit: number): T[] {
	return entries.toSorted((left, right) => size(right) - size(left)).slice(0, limit);
}

export interface ReviewOutcome {
	index: number;
	initExit: number;
	runtimeExit: number;
	/** The result uploads that the stand-in gateway received for the review's session. */
	uploads: number;
	initSamples: number;
	runtimeSamples: number;
}

export interface Verdict {
	passed: boolean;
	reasons: string[];
}

function exited(container: string, code: number): string {
	if (code === 137) {
		return `${container} exited 137 (killed: the cgroup memory limit or a signal)`;
	}
	return `${container} exited ${String(code)}`;
}

function reviewFailures(review: ReviewOutcome): string[] {
	const failures: string[] = [];
	if (review.initExit !== 0) {
		failures.push(exited("initializer", review.initExit));
	}
	if (review.initSamples < 1) {
		failures.push("the initializer has no samples");
	}
	if (review.runtimeExit === NOT_STARTED) {
		failures.push("the runtime did not start");
		return failures;
	}
	if (review.runtimeExit !== 0) {
		failures.push(exited("runtime", review.runtimeExit));
	}
	if (review.runtimeSamples < RUNTIME_SAMPLE_FLOOR) {
		failures.push(
			`the runtime has ${String(review.runtimeSamples)} samples, fewer than ${String(RUNTIME_SAMPLE_FLOOR)}`,
		);
	}
	if (review.uploads < 1) {
		failures.push("the gateway received no result upload");
	}
	return failures;
}

/**
 * Whether the run measured what it claims. The stand-in model is deterministic, so in a clean run
 * each practice of each review submits exactly once.
 */
export function verdict(run: {
	reviews: readonly ReviewOutcome[];
	practices: number;
	submits: number;
	malformed: readonly string[];
	errors: readonly string[];
}): Verdict {
	const reasons = [...run.errors];
	if (run.reviews.length === 0) {
		reasons.push("no review ran");
	}
	if (run.practices < 1) {
		reasons.push("the staged spec has no practices");
	}
	for (const review of run.reviews) {
		for (const failure of reviewFailures(review)) {
			reasons.push(`review ${String(review.index)}: ${failure}`);
		}
	}
	const expected = run.reviews.length * run.practices;
	if (run.submits !== expected) {
		reasons.push(
			`the model received ${String(run.submits)} result submissions, not ${String(expected)} (${String(run.reviews.length)} reviews × ${String(run.practices)} practices)`,
		);
	}
	if (run.malformed.length > 0) {
		reasons.push(
			`${String(run.malformed.length)} sampler lines were unreadable; the first: ${(run.malformed[0] ?? "").slice(0, 200)}`,
		);
	}
	return { passed: reasons.length === 0, reasons };
}

/** A whole number above zero, or an error that names the option. */
export function positiveInteger(value: string, option: string): number {
	const parsed = Number(value);
	if (!/^\d+$/u.test(value) || !Number.isSafeInteger(parsed) || parsed < 1) {
		throw new Error(`${option} must be a whole number above 0, but is "${value}"`);
	}
	return parsed;
}
