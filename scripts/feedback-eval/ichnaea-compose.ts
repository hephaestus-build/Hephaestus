/**
 * Composes one replicate of the composition set with the live model, for both arms, into `base-r<k>.json` and
 * `candidate-r<k>.json`. Cases alternate between the arms, so both meet the same endpoint conditions. A seeded coin per
 * case and replicate picks the arm that runs first, so neither arm always meets the endpoint first; the second arm's
 * trial starts only after the first arm's trial has ended (DN-41, DN-47). A case runs in
 * replicate k when the suite gives it more than k trials (scripts/feedback-eval/ichnaea/suite.json). Each case is one
 * Gradle invocation, as in the eval gate, so each stays under the live tier's time limit. A case that fails at the
 * endpoint, or for which the live test wrote nothing, measures the endpoint and not the composer: it runs again, up to
 * ATTEMPTS times in all, and `attempts-r<k>.jsonl` keeps every attempt. The decision imports only the last one. A case
 * that the composer itself got wrong is never run again. A case that sends more model calls than its bound stops the
 * run before the next request.
 * Usage: node scripts/feedback-eval/ichnaea-compose.ts --replicate <k> --base <checkout> --candidate <checkout> --out <dir>
 * Needs HEPHAESTUS_LIVE_LLM_BASE_URL, HEPHAESTUS_LIVE_LLM_API_KEY and HEPHAESTUS_LIVE_LLM_MODEL.
 */
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
	appendFileSync,
	existsSync,
	mkdirSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";

import { ENDPOINT_FAILURES } from "./compositions.ts";
import { trialsFor } from "./ichnaea-cases.ts";
import { composerSettings } from "./ichnaea-configuration.ts";

/** JSON from a file, unchecked: each caller checks the shape it reads. */
const parseJson = (text: string): unknown => JSON.parse(text);

const here = import.meta.dirname;

/** An arm of the comparison: its checkout and the result file it composes into. */
export interface Arm {
	name: string;
	root: string;
	file: string;
}

/** The variables that tell the live test of `arm` what to compose: the case, the result file and the arm's own settings. */
export function composeEnvironment(arm: Arm, caseId: string): Record<string, string> {
	const settings = composerSettings(arm.root);
	return {
		COMPOSITION_EVAL_CHECKED: arm.file,
		COMPOSITION_EVAL_CASES: caseId,
		COMPOSITION_EVAL_REASONING_EFFORT: settings.reasoningEffort,
		COMPOSITION_EVAL_MAX_OUTPUT_TOKENS: String(settings.maxOutputTokens),
	};
}

/** The case IDs that run in replicate `k`, in the case set's order. */
export function casesInReplicate(k: number): string[] {
	const suite = parseJson(readFileSync(path.join(here, "ichnaea", "suite.json"), "utf8"));
	if (!isRecord(suite) || typeof suite.replicates !== "number") {
		throw new Error("ichnaea/suite.json names no replicate count");
	}
	const counts = {
		replicates: suite.replicates,
		replicatesFor: Array.isArray(suite.replicatesFor)
			? suite.replicatesFor.flatMap((entry: unknown) =>
					isRecord(entry) &&
					typeof entry.slice === "string" &&
					typeof entry.value === "string" &&
					typeof entry.replicates === "number"
						? [{ slice: entry.slice, value: entry.value, replicates: entry.replicates }]
						: [],
				)
			: [],
	};
	return readFileSync(path.join(here, "ichnaea", "cases.jsonl"), "utf8")
		.split("\n")
		.filter((line) => line.trim() !== "")
		.flatMap((line) => {
			const c = parseJson(line);
			if (!isRecord(c) || typeof c.id !== "string") {
				throw new Error("ichnaea/cases.jsonl holds a case without an ID");
			}
			const slices = isRecord(c.slices)
				? Object.fromEntries(Object.entries(c.slices).map(([key, value]) => [key, String(value)]))
				: {};
			return trialsFor(counts, slices) > k ? [c.id] : [];
		});
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null && !Array.isArray(value);

const WINDOWS = process.platform === "win32";

/** The seed of the coin that orders each case's arms, unless a run registers its own. */
export const COIN_SEED = "composition-set-coin-1";

/**
 * The seed of this run's coin: `COMPOSE_COIN_SEED` when a run's registered plan names one, else `COIN_SEED`. Every
 * attempt line records it, so a run's order can be recomputed from its log.
 */
export const runSeed = (env: NodeJS.ProcessEnv = process.env): string =>
	env.COMPOSE_COIN_SEED !== undefined && env.COMPOSE_COIN_SEED !== ""
		? env.COMPOSE_COIN_SEED
		: COIN_SEED;

/**
 * The arm that runs first for `caseId` in replicate `k`: a fair coin from the SHA-256 of the seed, the case and the
 * replicate. The same inputs always give the same order, and the attempts log records it.
 */
export function firstArm(caseId: string, k: number, seed = COIN_SEED): "base" | "candidate" {
	const digest = createHash("sha256")
		.update(`${seed}\u0000${caseId}\u0000${String(k)}`)
		.digest();
	return (digest[0] ?? 0) < 128 ? "base" : "candidate";
}

/** `arms` in the order the coin gives for `caseId` in replicate `k`. */
export function armsInOrder<T extends { name: string }>(
	arms: readonly T[],
	caseId: string,
	k: number,
	seed = COIN_SEED,
): T[] {
	const first = firstArm(caseId, k, seed);
	return [...arms].toSorted((a, b) => Number(b.name === first) - Number(a.name === first));
}

/** Attempts of one case in one arm: the first, and two more when the endpoint fails it. */
export const ATTEMPTS = 3;

/** The cases a result file holds, unchecked beyond an ID each. */
function casesOf(file: string): Record<string, unknown>[] {
	if (!existsSync(file)) {
		return [];
	}
	const parsed = parseJson(readFileSync(file, "utf8"));
	return isRecord(parsed) && Array.isArray(parsed.cases)
		? parsed.cases.filter(
				(entry): entry is Record<string, unknown> =>
					isRecord(entry) && typeof entry.id === "string",
			)
		: [];
}

/**
 * Why an attempt of `caseId` says nothing about the composer, or null when it does: the endpoint failed it, or the live
 * test recorded nothing for it.
 */
export function endpointFailure(file: string, caseId: string): string | null {
	const entry = casesOf(file).find((c) => c.id === caseId);
	if (entry === undefined) {
		return "NOT_RECORDED";
	}
	return typeof entry.failure === "string" && ENDPOINT_FAILURES.has(entry.failure)
		? entry.failure
		: null;
}

/** Removes `caseId` from a result file, so the live test composes it again instead of resuming past it. */
export function forget(file: string, caseId: string): void {
	const parsed = parseJson(readFileSync(file, "utf8"));
	if (!isRecord(parsed) || !Array.isArray(parsed.cases)) {
		throw new Error(`${file} holds no cases`);
	}
	parsed.cases = parsed.cases.filter((entry: unknown) => !(isRecord(entry) && entry.id === caseId));
	writeFileSync(file, JSON.stringify(parsed));
}

/** A recorded case's HTTP requests: `requests` when present, else its model calls, else its bound. */
function callsOf(entry: Record<string, unknown>, bound: number): number {
	if (typeof entry.requests === "number") {
		return entry.requests;
	}
	return typeof entry.calls === "number" ? entry.calls : bound;
}

/** Every HTTP request a result file recorded, by case: `requests` when present, else its model calls. */
function recordedCalls(file: string, bound: number): Map<string, number> {
	if (!existsSync(file)) {
		return new Map();
	}
	const parsed = parseJson(readFileSync(file, "utf8"));
	const cases: unknown[] = isRecord(parsed) && Array.isArray(parsed.cases) ? parsed.cases : [];
	return new Map(
		cases.flatMap((entry): [string, number][] =>
			isRecord(entry) && typeof entry.id === "string" ? [[entry.id, callsOf(entry, bound)]] : [],
		),
	);
}

/** What one trial of one arm needs: its arm, case and replicate, the run's seed, the attempts log and its pair order. */
interface TrialPosition {
	arm: Arm;
	caseId: string;
	k: number;
	seed: string;
	attempts: string;
	first: string | undefined;
	position: number;
}

/**
 * Composes one case for one arm, up to ATTEMPTS times: an endpoint failure, or a case for which the live test wrote
 * nothing, runs again, and every attempt is logged. A case that sends more model calls than its bound stops the run.
 */
function composeTrial({ arm, caseId, k, seed, attempts, first, position }: TrialPosition): void {
	for (let attempt = 1; attempt <= ATTEMPTS; attempt += 1) {
		// The harness's clock, not the live test's, so the pair order can be checked from the log alone.
		const startedAt = new Date().toISOString();
		// Each arm runs its own checkout's wrapper. Windows runs the batch wrapper, which needs a shell (run-gradlew.ts).
		const live = spawnSync(
			WINDOWS ? "gradlew.bat" : "./gradlew",
			[":application:liveTest", "--tests", "CompositionEvalLiveTest", "--console=plain"],
			{
				cwd: path.join(arm.root, "server"),
				stdio: "inherit",
				env: { ...process.env, ...composeEnvironment(arm, caseId) },
				shell: WINDOWS,
			},
		);
		// A wrapper that cannot start says nothing about the endpoint or the composer, so it stops the run.
		if (live.error !== undefined) {
			throw new Error(`the live test of ${arm.name} could not start: ${live.error.message}`);
		}
		const endedAt = new Date().toISOString();
		const bound = composerSettings(arm.root).callsPerCase;
		const calls = recordedCalls(arm.file, bound).get(caseId) ?? 0;
		if (calls > bound) {
			throw new Error(
				`${caseId} (${arm.name}) sent ${calls} model calls, over its bound of ${bound}`,
			);
		}
		const failure = endpointFailure(arm.file, caseId);
		appendFileSync(
			attempts,
			`${JSON.stringify({ case: caseId, arm: arm.name, replicate: k, attempt, calls, failure, first, position, seed, startedAt, endedAt })}\n`,
		);
		if (failure === null || attempt === ATTEMPTS) {
			return;
		}
		if (failure !== "NOT_RECORDED") {
			forget(arm.file, caseId);
		}
	}
}

if (import.meta.main) {
	const { values } = parseArgs({
		options: {
			replicate: { type: "string" },
			base: { type: "string" },
			candidate: { type: "string" },
			out: { type: "string" },
		},
	});
	if (
		values.replicate === undefined ||
		values.base === undefined ||
		values.candidate === undefined ||
		values.out === undefined
	) {
		throw new Error("--replicate, --base, --candidate and --out are required");
	}
	const k = Number(values.replicate);
	const out = path.resolve(values.out);
	mkdirSync(out, { recursive: true });
	const arms: Arm[] = [
		{ name: "base", root: path.resolve(values.base) },
		{ name: "candidate", root: path.resolve(values.candidate) },
	].map((arm) => ({ ...arm, file: path.join(out, `${arm.name}-r${k}.json`) }));
	// A checkout without its own settings stops the run before the first model call.
	for (const arm of arms) {
		composerSettings(arm.root);
	}
	// The live test resumes a file it finds; every replicate starts from none.
	for (const arm of arms) {
		rmSync(arm.file, { force: true });
	}
	const attempts = path.join(out, `attempts-r${k}.jsonl`);
	const seed = runSeed();
	rmSync(attempts, { force: true });
	for (const caseId of casesInReplicate(k)) {
		const ordered = armsInOrder(arms, caseId, k, seed);
		for (const [position, arm] of ordered.entries()) {
			composeTrial({
				arm,
				caseId,
				k,
				seed,
				attempts,
				first: ordered[0]?.name,
				position: position + 1,
			});
		}
	}
	for (const arm of arms) {
		if (!existsSync(arm.file)) {
			throw new Error(
				`no result file for ${arm.name}, replicate ${k}: the live test wrote nothing`,
			);
		}
	}
}
