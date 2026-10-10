/** The runner's side of one script's process: its grants, its requests, its deadline and its result. */
import { fork } from "node:child_process";
import { once } from "node:events";
import { existsSync } from "node:fs";
import { realpath } from "node:fs/promises";
import path from "node:path";

import {
	metaSchema,
	MODEL_SLOTS,
	precomputedSchema,
	type FailedStatus,
	type ModelSlot,
	type Need,
	type ParsedMeta,
	type SlotModels,
} from "./contract.ts";
import { isInDiff } from "./diff-parser.ts";
import { grep, type GrepMatch } from "./grep.ts";
import { childMessageSchema, type ChildJob, type ChildMessage, type RunnerMessage } from "./ipc.ts";
import {
	renderDefinition,
	settleLeads,
	type DefinitionResult,
	type SlotReport,
	type Sources,
} from "./leads.ts";
import { modelCall, type ModelBudget } from "./models-host.ts";
import { parsePositionalResult, renderPositional } from "./practice-contract.ts";
import type { PracticeResult } from "./types.ts";

/** Heap per script, in MiB. */
const CHILD_HEAP_MB = 256;
/** A definition script may wait for models, so it keeps a longer limit than a positional script. */
const DEFINITION_MS = 60_000;
/**
 * A definition script's model calls and signal end this long before its process is killed, so a
 * model that does not answer leaves its step unrated and the script still returns what it found.
 */
const RETURN_MS = 5000;
/** Default token ceiling of one practice. */
const PRACTICE_TOKENS = 20_000;
/** Messages a child may send while its earlier ones are still being answered. */
const MAX_IN_FLIGHT = 32;
/** Default time limit of a positional script. */
export const POSITIONAL_MS = 15_000;
/** The most of a failed script's error that its result keeps. */
export const MAX_ERROR_CHARS = 2000;

interface ScriptEnvironment {
	readable: readonly string[];
	sources: Sources;
	metadata: ChildJob["metadata"];
	changeDir: string;
	now: string;
	/** Epoch milliseconds: no script runs past it. */
	stageEnd: number;
	/** Upper bound of a positional script's time, from `--timeout`. */
	positionalMs: number;
	/** The models of one practice: every call names the practice to the proxy. */
	models: (practice: string) => Partial<SlotModels>;
	budget: ModelBudget;
	/** The script's own diagnostics go to the runner's log. */
	log: (line: string) => void;
}

/**
 * "unknown": the script failed before it said what it is, or never started, so nothing is known about the
 * models it uses. Only a valid result without `meta` shows that a script is positional.
 */
export type ScriptOutcome =
	| { contract: "positional" | "unknown"; result: PracticeResult }
	| { contract: "definition"; result: DefinitionResult };

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** A script that failed before it sent `meta`, or never started. */
function failure(slug: string, message: string, status: FailedStatus = "error"): ScriptOutcome {
	return {
		contract: "unknown",
		result: {
			practice: slug,
			status,
			hints: [],
			metrics: { error: 1 },
			directions: [`Script failed: ${message}`],
			error: message,
		},
	};
}

/**
 * The folders a script's process may read: the precompute install, the dependency folders that the
 * library resolves from, and the given inputs. In the image the dependencies are inside the install;
 * in a checkout they are above it.
 */
export function readableRoots(inputs: readonly string[]): string[] {
	const install = path.dirname(import.meta.dirname);
	// `@types/node` declares the permission API always present; without `--permission` it is undefined.
	const permission = process.permission as NodeJS.ProcessPermission | undefined;
	const dependencies: string[] = [];
	for (let dir = install; ; dir = path.dirname(dir)) {
		const candidate = path.join(dir, "node_modules");
		// Under the permission model a probe of an ungranted folder throws: a child gets only what the
		// runner may read itself.
		if ((permission?.has("fs.read", candidate) ?? true) && existsSync(candidate)) {
			dependencies.push(candidate);
		}
		if (path.dirname(dir) === dir) {
			break;
		}
	}
	return [install, ...dependencies, ...inputs]
		.filter((dir) => dir !== "")
		.map((dir) => path.resolve(dir));
}

/**
 * The two files of a practice that ended: its result `<slug>.json`, whose `contract` tells the
 * server's report whether the script's models are known, and its section `<slug>.md`, which points
 * at `jsonPath` for what it leaves out.
 */
export function practiceFiles(
	outcome: ScriptOutcome,
	durationMs: number,
	jsonPath: string,
	contextReference: string,
): { json: string; section: string } {
	const pointer = `\`${jsonPath}\``;
	return {
		json: JSON.stringify({ ...outcome.result, contract: outcome.contract, durationMs }, null, 2),
		section:
			outcome.contract === "definition"
				? renderDefinition(outcome.result, contextReference, pointer)
				: renderPositional(outcome.result, pointer),
	};
}

/** Whether `dir` resolves inside a readable root. grep follows a symbolic link that it is given. */
async function isReadable(readable: readonly string[], dir: string): Promise<boolean> {
	const resolved = await realpath(dir).catch(() => undefined);
	const roots = await Promise.all(readable.map(async (root) => realpath(root).catch(() => root)));
	return (
		resolved !== undefined &&
		roots.some((root) => resolved === root || resolved.startsWith(`${root}${path.sep}`))
	);
}

/** A positional result, with the changed-line hints that the change does not hold held back. */
function positional(slug: string, value: unknown, env: ScriptEnvironment): PracticeResult {
	const findings = parsePositionalResult(value, `Script ${slug}`);
	const hints = findings.hints.filter(
		(h) => !h.inDiff || isInDiff(env.sources.change, h.file, h.line),
	);
	const dropped = findings.hints.length - hints.length;
	return {
		practice: slug,
		status: "ok",
		hints,
		dropped,
		metrics: findings.metrics,
		directions: [
			...findings.directions.slice(0, 10),
			...(dropped > 0
				? [
						`${dropped} hint(s) on changed lines are not shown: their file and line are not in the change.`,
					]
				: []),
		],
	};
}

export async function runScript(
	slug: string,
	modulePath: string,
	env: ScriptEnvironment,
): Promise<ScriptOutcome> {
	const left = env.stageEnd - Date.now();
	if (left <= 0) {
		return failure(slug, "the stage deadline passed before this practice started", "timeout");
	}
	const models = env.models(slug);
	const started = Date.now();
	const deadline = started + Math.min(DEFINITION_MS, left);
	const callsEnd = Math.max(started, deadline - RETURN_MS);
	const child = fork(path.join(import.meta.dirname, "..", "child.ts"), [], {
		env: {},
		execArgv: [
			"--permission",
			...env.readable.map((dir) => `--allow-fs-read=${dir}`),
			`--max-old-space-size=${CHILD_HEAP_MB}`,
		],
		stdio: ["ignore", "pipe", "pipe", "ipc"],
		serialization: "advanced",
	});
	const prefix = (chunk: Buffer) => {
		for (const line of chunk.toString().split("\n")) {
			if (line !== "") {
				env.log(`  [${slug}] ${line}`);
			}
		}
	};
	child.stdout?.on("data", prefix);
	child.stderr?.on("data", prefix);
	// Every call the runner makes for this script ends with the script.
	const stopped = new AbortController();
	child.on("close", () => stopped.abort());
	const reply = (message: RunnerMessage) => {
		if (child.connected) {
			child.send(message);
		}
	};
	// Only a kill at one of these limits is a timeout. The kernel can also kill the process, for
	// example when it runs out of memory, and more time does not help then.
	const limit = { reached: false };
	const stopAtLimit = () => {
		limit.reached = true;
		child.kill("SIGKILL");
	};
	const deadlineLimit = setTimeout(stopAtLimit, deadline - Date.now());
	// A positional script is stopped at its own shorter limit; a definition script says what it is
	// with its first message, `meta`, and keeps the longer one.
	const positionalLimit = setTimeout(
		stopAtLimit,
		Math.min(env.positionalMs, deadline - Date.now()),
	);

	let settled: ScriptOutcome | undefined;
	let meta: ParsedMeta | undefined;
	const available: Partial<Record<ModelSlot, string>> = {};
	const account = { left: 0 };
	const report: Partial<Record<ModelSlot, SlotReport>> = {};
	const calls = new Map<number, AbortController>();
	let inFlight = 0;

	const definitionOutcome = (
		status: DefinitionResult["status"],
		parts: Partial<DefinitionResult> = {},
	): ScriptOutcome => ({
		contract: "definition",
		result: {
			practice: slug,
			contract: "definition",
			status,
			models: report,
			leads: [],
			facts: {},
			unrated: [],
			directions: [],
			kinds: meta?.kinds ?? {},
			dropped: 0,
			...parts,
		},
	});
	// Only an accepted `meta` says which models a script uses; a script that failed before it is unknown.
	const fail = (error: string, status: FailedStatus = "error"): ScriptOutcome => {
		const message = error.slice(0, MAX_ERROR_CHARS).toWellFormed();
		return meta === undefined
			? failure(slug, message, status)
			: definitionOutcome(status, { directions: [`Script failed: ${message}`], error: message });
	};

	const answerModel = async (message: Extract<ChildMessage, { kind: "model" }>): Promise<void> => {
		const { id, slot, label } = message;
		const slotReport = report[slot];
		const model = models[slot];
		if (available[slot] === undefined || model === undefined || slotReport === undefined) {
			reply({
				kind: "model-failed",
				id,
				reason: "unavailable",
				message: `no ${slot} model is available to this script`,
				tokensLeft: account.left,
			});
			return;
		}
		const call = new AbortController();
		calls.set(id, call);
		const signal = AbortSignal.any([
			stopped.signal,
			call.signal,
			AbortSignal.timeout(Math.max(0, callsEnd - Date.now())),
		]);
		const asked = modelCall(slot, model, message.options, signal);
		const outcome =
			"reason" in asked ? asked : await env.budget.run(account, asked.reserve, signal, asked.run);
		calls.delete(id);
		if ("value" in outcome) {
			reply({ kind: "model", id, answer: outcome.value, tokensLeft: account.left });
		} else {
			slotReport.notRated[outcome.reason] = (slotReport.notRated[outcome.reason] ?? 0) + 1;
			env.log(
				`  [${slug}] ${slot} call${label === "" ? "" : ` in ${label}`} not rated: ${outcome.reason}`,
			);
			reply({
				kind: "model-failed",
				id,
				reason: outcome.reason,
				message: `${slot} call not rated: ${outcome.reason}`,
				tokensLeft: account.left,
			});
		}
	};

	const acceptMeta = (value: unknown) => {
		clearTimeout(positionalLimit);
		const parsed = metaSchema.safeParse(value);
		if (!parsed.success || meta !== undefined) {
			settled ??= fail(
				parsed.success
					? "meta was sent twice"
					: `meta: ${parsed.error.issues[0]?.message ?? "invalid"}`,
			);
			child.kill("SIGKILL");
			return;
		}
		meta = parsed.data;
		const needs: Partial<Record<ModelSlot, Need>> = meta.models ?? {};
		for (const slot of MODEL_SLOTS) {
			const need = needs[slot];
			if (need === undefined) {
				continue;
			}
			const modelId = models[slot]?.modelId;
			report[slot] = { need, bound: modelId !== undefined, notRated: {} };
			if (modelId !== undefined) {
				available[slot] = modelId;
			}
		}
		if (Object.values(report).some((r) => r.need === "required" && !r.bound)) {
			settled ??= definitionOutcome("skipped");
			child.kill("SIGKILL");
			return;
		}
		account.left = meta.tokens ?? PRACTICE_TOKENS;
		reply({ kind: "meta-accepted", models: available, tokens: account.left });
	};

	const acceptResult = (value: unknown) => {
		if (settled !== undefined) {
			return;
		}
		if (meta === undefined) {
			try {
				settled = { contract: "positional", result: positional(slug, value, env) };
			} catch (error) {
				settled = fail(messageOf(error));
			}
			return;
		}
		const parsed = precomputedSchema.safeParse(value);
		if (!parsed.success) {
			const issue = parsed.error.issues[0];
			settled = fail(`result: ${issue ? `${issue.path.join(".")} ${issue.message}` : "invalid"}`);
			return;
		}
		const { leads, dropped } = settleLeads(parsed.data, meta.kinds, env.sources);
		settled = definitionOutcome("ok", {
			leads,
			facts: parsed.data.facts ?? {},
			unrated: parsed.data.unrated ?? [],
			directions: parsed.data.directions ?? [],
			dropped,
		});
	};

	const answerGrep = async (message: Extract<ChildMessage, { kind: "grep" }>) => {
		if (!(await isReadable(env.readable, message.dir))) {
			reply({
				kind: "grep-failed",
				id: message.id,
				message: "grep searches only the workspace inputs",
			});
			return;
		}
		let matches: GrepMatch[];
		try {
			matches = await grep(message.pattern, message.dir, message.opts);
		} catch (error) {
			reply({ kind: "grep-failed", id: message.id, message: messageOf(error) });
			return;
		}
		reply({ kind: "grep", id: message.id, matches });
	};

	/**
	 * Answer one search or model call; it counts as in flight until its reply is sent. A request that
	 * the runner cannot answer, such as options without a JSON form, stops this script and no other.
	 */
	const answerRequest = async (message: Extract<ChildMessage, { kind: "grep" | "model" }>) => {
		inFlight += 1;
		try {
			await (message.kind === "model" ? answerModel(message) : answerGrep(message));
		} catch (error) {
			settled ??= fail(
				`the runner could not answer a ${message.kind} request: ${messageOf(error)}`,
			);
			child.kill("SIGKILL");
		} finally {
			inFlight -= 1;
		}
	};

	child.on("message", (raw: unknown) => {
		const parsed = childMessageSchema.safeParse(raw);
		if (!parsed.success) {
			settled ??= fail("the script's process sent a message the runner does not accept");
			child.kill("SIGKILL");
			return;
		}
		const message = parsed.data;
		switch (message.kind) {
			case "meta": {
				acceptMeta(message.meta);
				return;
			}
			case "log": {
				env.log(`  [${slug}] ${message.text}`);
				return;
			}
			case "cancel": {
				calls.get(message.id)?.abort();
				return;
			}
			case "result": {
				clearTimeout(positionalLimit);
				acceptResult(message.value);
				return;
			}
			case "failed": {
				clearTimeout(positionalLimit);
				settled ??= fail(message.message);
				return;
			}
			case "grep":
			case "model": {
				if (inFlight >= MAX_IN_FLIGHT) {
					settled ??= fail(`more than ${MAX_IN_FLIGHT} requests at once`);
					child.kill("SIGKILL");
					return;
				}
				void answerRequest(message);
			}
		}
	});
	child.on("error", (error) => {
		settled ??= fail(messageOf(error));
	});
	const closed = once(child, "close");
	const job: ChildJob = {
		modulePath,
		slug,
		repoPath: env.sources.repo,
		diffFiles: env.sources.change,
		metadata: env.metadata,
		contextDir: env.sources.contextDir,
		changeDir: env.changeDir,
		contextReference: env.sources.contextReference,
		now: env.now,
		deadline: callsEnd,
	};
	reply({ kind: "job", job });
	const exit: readonly unknown[] = await closed;
	clearTimeout(positionalLimit);
	clearTimeout(deadlineLimit);
	if (settled !== undefined) {
		return settled;
	}
	if (limit.reached) {
		return fail(`Timeout after ${Date.now() - started}ms`, "timeout");
	}
	const signal = exit[1];
	return fail(
		typeof signal === "string"
			? `the script's process was killed by ${signal}`
			: "the script's process ended without a result",
	);
}
