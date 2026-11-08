/**
 * The host's side of one custom program's process: its grants, its requests, its deadline and its
 * final value. The host answers each request once, and ends the process at its review or at the
 * practice's deadline.
 */
import { supportsForcedToolChoice } from "./model-capabilities.ts";

import { fork } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import {
	MODEL_SLOTS,
	parseMeta,
	parseReviewOptions,
	type Candidate,
	type ModelSlot,
	type ParsedMeta,
	type Reason,
	type ReviewOptions,
	type SlotModels,
	type StopReason,
} from "./contract.ts";
import type { EvidenceReader } from "./evidence-reader.ts";
import {
	childMessageSchema,
	MAX_IPC_MESSAGE_BYTES,
	type AbortReason,
	type ChildMessage,
	type HostMessage,
	type ProgramJob,
} from "./ipc.ts";
import { isJsonObject } from "./json.ts";
import { modelCall, type ModelBudget } from "./models-host.ts";
import { reasonOf, UnratedError } from "./practice-script.ts";
import { isPracticeScriptPath } from "./script-imports.ts";

const CHILD_HEAP_MB = 256;
/** A program's process is killed this long after its deadline, so a program that unwinds still returns. */
export const RETURN_MS = 5000;
/** The most memory that one script process can hold on its heap. */
export const SCRIPT_HEAP_BYTES = CHILD_HEAP_MB * 1024 * 1024;

export class ProgramSettlementError extends Error {
	override name = "ProgramSettlementError";
}
const MAX_IN_FLIGHT = 32;
export const MAX_ERROR_CHARS = 2000;
const MAX_OUTPUT_CHARS = 64 * 1024;

export interface SlotReport {
	slot: ModelSlot;
	need: "required" | "optional";
	bound: boolean;
	failures: Partial<Record<Reason, number>>;
}

/** What starts a program's process: its Node flags, and how long it may run. */
export interface ProgramStart {
	execArgv: readonly string[];
	timeoutMs: number;
}

/** A program's process. It may run on another side of a trust boundary, so what it says is untrusted. */
export interface ProgramProcess {
	readonly connected: boolean;
	send: (message: HostMessage) => void;
	/** Ends the process at once. */
	kill: () => void;
	onOutput: (listener: (chunk: Buffer) => void) => void;
	onMessage: (listener: (raw: unknown) => void) => void;
	/** `signal` names what killed the process, if a signal did. */
	onClose: (listener: (signal: string | null) => void) => void;
	onError: (listener: (failure: Error) => void) => void;
}

/** The program's process as a child of this one. */
export function forkedProgram(start: ProgramStart): ProgramProcess {
	const child = fork(
		path.join(import.meta.dirname, "../..", `child${path.extname(import.meta.filename)}`),
		[],
		{
			env: {},
			execArgv: [...start.execArgv],
			stdio: ["ignore", "pipe", "pipe", "ipc"],
			serialization: "advanced",
		},
	);
	return {
		get connected() {
			return child.connected;
		},
		send: (message) => {
			child.send(message);
		},
		kill: () => {
			child.kill("SIGKILL");
		},
		onOutput: (listener) => {
			child.stdout?.on("data", listener);
			child.stderr?.on("data", listener);
		},
		onMessage: (listener) => {
			child.on("message", listener);
		},
		onClose: (listener) => {
			child.once("close", (_code, signal) => {
				listener(signal);
			});
		},
		onError: (listener) => {
			child.on("error", listener);
		},
	};
}

/** What the host gives one practice's program. */
export interface ProgramServices {
	/** Starts the program's process. */
	start: (start: ProgramStart) => ProgramProcess;
	reader: EvidenceReader;
	/** The practice's direct models, with the credential: every call names the practice to the proxy. */
	models: Partial<SlotModels>;
	budget: ModelBudget;
	/** A direct model call waits for its answer. The returned function records the answer. */
	awaitModel: () => () => void;
	/** Repeated lost responses end the attempt as an infrastructure failure, not a program result. */
	gatewayUnavailable?: () => void;
	/** Run one review of this practice. It rejects with `UnratedError` when the session gives no result. */
	review: (options: ReviewOptions, signal: AbortSignal) => Promise<Candidate>;
	signal: AbortSignal;
	log: (line: string) => void;
}

export interface ProgramExecution {
	value?: unknown;
	/** What the kits could not trust in the capture while the program ran. */
	captureFailures?: readonly string[];
	failure?: { stopReason: StopReason; error: string; skipped?: true };
	models: SlotReport[];
	calls: { review: number } & Record<ModelSlot, number>;
}

interface OpenRequest {
	kind: "model" | "read" | "review";
	call: AbortController;
	slot?: ModelSlot;
	refused?: true;
}

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));
const cut = (text: string) => text.slice(0, MAX_ERROR_CHARS).toWellFormed();

export const noCalls = (): ProgramExecution["calls"] => ({
	review: 0,
	chat: 0,
	decision: 0,
	embedding: 0,
	reranking: 0,
});

/**
 * The folders a program's process may read: the toolkit install, the dependency folders that the
 * library resolves from, and the given inputs. In the image the dependencies are inside the install;
 * in a checkout they are above it.
 */
export function readableRoots(inputs: readonly string[]): string[] {
	const install = path.resolve(import.meta.dirname, "../..");
	// `@types/node` declares the permission API always present; without `--permission` it is undefined.
	const permission = process.permission as NodeJS.ProcessPermission | undefined;
	const dependencies: string[] = [];
	for (let dir = install; ; dir = path.dirname(dir)) {
		const candidate = path.join(dir, "node_modules");
		// Under the permission model a probe of an ungranted folder throws: a child gets only what the
		// host may read itself.
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

export function acceptModels(
	meta: ParsedMeta,
	models: Partial<SlotModels>,
): { report: SlotReport[]; available: Partial<Record<ModelSlot, string>>; missing: ModelSlot[] } {
	const report: SlotReport[] = [];
	const available: Partial<Record<ModelSlot, string>> = {};
	for (const slot of MODEL_SLOTS) {
		const need = meta.models?.[slot];
		if (need === undefined) {
			continue;
		}
		const modelId = models[slot]?.modelId;
		report.push({ slot, need, bound: modelId !== undefined, failures: {} });
		if (modelId !== undefined) {
			available[slot] = modelId;
		}
	}
	return {
		report,
		available,
		missing: report.filter((r) => r.need === "required" && !r.bound).map((r) => r.slot),
	};
}

export function abortReasonOf(signal: AbortSignal): AbortReason {
	const reason: unknown = signal.reason;
	return reason instanceof UnratedError &&
		(reason.reason === "budget" || reason.reason === "cancelled")
		? reason.reason
		: "deadline";
}

const unratedBy = (reason: AbortReason) => (reason === "cancelled" ? "deadline" : reason);

/** Why a run that `signal` ended has no result. A program never hears `unavailable`: its abort stays a deadline. */
export function stopReasonOf(signal: AbortSignal) {
	const reason: unknown = signal.reason;
	return reason instanceof UnratedError && reason.reason === "unavailable"
		? "unavailable"
		: abortReasonOf(signal);
}

/**
 * Run one custom program in its own process, until it returns, fails, asks for its review or reaches
 * its deadline. Its value is returned unchecked: the host validates it as a candidate.
 */
export async function runCustomProgram(
	modulePath: string,
	job: ProgramJob,
	services: ProgramServices,
): Promise<ProgramExecution> {
	const { slug } = job;
	const calls = noCalls();
	if (services.signal.aborted || Date.now() >= job.deadline) {
		const stopReason = services.signal.aborted ? stopReasonOf(services.signal) : "deadline";
		return {
			failure: {
				stopReason,
				error: `the host stopped this program before it started: ${stopReason}`,
			},
			models: [],
			calls,
		};
	}
	let report: SlotReport[] = [];
	// Its own file and the toolkit: another practice's program and inputs stay out of reach.
	const staged = path.dirname(path.dirname(modulePath));
	const readable = readableRoots([modulePath, path.join(staged, "lib"), path.join(staged, "kits")]);
	const child = services.start({
		execArgv: [
			"--permission",
			...readable.map((dir) => `--allow-fs-read=${dir}`),
			`--max-old-space-size=${CHILD_HEAP_MB}`,
		],
		timeoutMs: Math.max(0, job.deadline - Date.now()) + RETURN_MS,
	});
	let output = 0;
	const prefix = (chunk: Buffer) => {
		if (output >= MAX_OUTPUT_CHARS) {
			return;
		}
		const text = chunk.toString().slice(0, MAX_OUTPUT_CHARS - output);
		output += text.length;
		for (const line of text.split("\n")) {
			if (line !== "") {
				services.log(`  [${slug}] ${line}`);
			}
		}
		if (output >= MAX_OUTPUT_CHARS) {
			services.log(`  [${slug}] (more output is not logged)`);
		}
	};
	child.onOutput(prefix);

	const stopped = new AbortController();
	let ended = false;
	// The program ends at its review (see answerReview), so its exit does not stop the session it handed to.
	const handOff = { happened: false };
	const reviewEnded = Promise.withResolvers<undefined>();
	const processGone = Promise.withResolvers<string | null>();
	const endProgram = () => {
		ended = true;
		stopped.abort(new UnratedError("deadline", "the program ended"));
	};
	child.onClose((exitSignal) => {
		processGone.resolve(exitSignal);
		if (!handOff.happened) {
			endProgram();
		}
	});
	const signal = AbortSignal.any([
		stopped.signal,
		services.signal,
		AbortSignal.timeout(Math.max(0, job.deadline - Date.now())),
	]);
	let aborted: AbortReason | undefined;
	/** Why the host stopped the program. The program hears only `aborted`, which has no word for a lost side. */
	let hostStop: StopReason | undefined;
	const open = new Map<number, OpenRequest>();
	let lastRequestId = -1;
	const outstanding = new Map<OpenRequest, Promise<void>>();
	const countUnrated = (slot: ModelSlot, reason: Reason) => {
		const slotReport = report.find((r) => r.slot === slot);
		if (slotReport !== undefined) {
			slotReport.failures[reason] = (slotReport.failures[reason] ?? 0) + 1;
		}
	};
	const reply = (message: Exclude<HostMessage, { kind: "job" | "abort" | "meta-accepted" }>) => {
		if (open.has(message.id) && child.connected && !handOff.happened) {
			child.send(message);
		}
		// Only now: a reply that could not go leaves the request open for its failure.
		open.delete(message.id);
	};
	const refuseRequest = (kind: "model" | "read" | "review", id: number, reason: AbortReason) => {
		if (!child.connected) {
			return;
		}
		const message = `stopped: ${reason}`;
		const failures: Record<typeof kind, HostMessage> = {
			model: { kind: "model-failed", id, reason, message, tokensLeft: services.budget.left },
			read: { kind: "read-failed", id, reason, message },
			review: { kind: "review-failed", id, reason, message },
		};
		child.send(failures[kind]);
	};
	const sendAbort = (reason: AbortReason, stopReason: StopReason = reason) => {
		if (aborted !== undefined) {
			return;
		}
		aborted = reason;
		hostStop = stopReason;
		settled = {
			failure: { stopReason, error: `the host stopped this program: ${stopReason}` },
		};
		if (child.connected) {
			child.send({ kind: "abort", reason } satisfies HostMessage);
		}
		// Count failures before replying; the child can return as soon as it receives a failure.
		for (const [id, request] of open) {
			request.refused = true;
			request.call.abort(new UnratedError(reason, `the host stopped this program: ${reason}`));
			if (request.slot !== undefined) {
				countUnrated(request.slot, stopReason === "unavailable" ? stopReason : unratedBy(reason));
			}
			refuseRequest(request.kind, id, reason);
		}
		open.clear();
		killLater = setTimeout(stopAtLimit, RETURN_MS);
	};
	signal.addEventListener(
		"abort",
		() => {
			if (!ended) {
				sendAbort(abortReasonOf(signal), stopReasonOf(signal));
				return;
			}
			for (const { call } of open.values()) {
				call.abort(new UnratedError("error", "the program ended"));
			}
			open.clear();
		},
		{ once: true },
	);

	const limit = { reached: false };
	const stopAtLimit = () => {
		limit.reached = true;
		child.kill();
		reviewEnded.resolve(undefined);
	};
	let killLater: ReturnType<typeof setTimeout> | undefined;

	let settled: Omit<ProgramExecution, "models" | "calls"> | undefined;
	let finished = false;
	let meta: ParsedMeta | undefined;
	let accepted: Partial<Record<ModelSlot, string>> = {};

	const fail = (stopReason: StopReason, error: string) => {
		settled ??= { failure: { stopReason, error: cut(error) } };
		child.kill();
	};

	const answerModel = async (
		message: Extract<ChildMessage, { kind: "model" }>,
		request: OpenRequest,
		call: AbortSignal,
	) => {
		const { id, slot, label } = message;
		const model = services.models[slot];
		if (
			accepted[slot] === undefined ||
			model === undefined ||
			!report.some((r) => r.slot === slot)
		) {
			reply({
				kind: "model-failed",
				id,
				reason: "unavailable",
				message: `no ${slot} model is available to this program`,
				tokensLeft: services.budget.left,
			});
			return;
		}
		const asked = modelCall(slot, model, message.options, call, services.gatewayUnavailable);
		// A call counts as use only once the budget lets it reach the proxy.
		const outcome =
			"reason" in asked
				? asked
				: await services.budget.run(asked.reserve, call, async () => {
						calls[slot] += 1;
						const answered = services.awaitModel();
						try {
							return await asked.run();
						} finally {
							answered();
						}
					});
		if ("value" in outcome) {
			reply({ kind: "model", id, answer: outcome.value, tokensLeft: services.budget.left });
			return;
		}
		if (request.refused === true) {
			return;
		}
		countUnrated(slot, outcome.reason);
		services.log(
			`  [${slug}] ${slot} call${label === "" ? "" : ` in ${label}`} not rated: ${outcome.reason}`,
		);
		reply({
			kind: "model-failed",
			id,
			reason: outcome.reason,
			message: `${slot} call not rated: ${outcome.reason}`,
			tokensLeft: services.budget.left,
		});
	};

	/**
	 * A script returns what `ctx.review` returned, so its process has nothing left to do while the model works.
	 * The host ends it now and returns the session's candidate itself. Its other requests end with it.
	 */
	const answerReview = async (
		message: Extract<ChildMessage, { kind: "review" }>,
		call: AbortSignal,
	) => {
		const { id } = message;
		const parsed = parseReviewOptions(message.options);
		if ("error" in parsed) {
			reply({ kind: "review-failed", id, reason: "error", message: parsed.error });
			return;
		}
		handOff.happened = true;
		calls.review += 1;
		for (const [other, request] of open) {
			if (other !== id) {
				request.refused = true;
				request.call.abort(new UnratedError("cancelled", "the program ended at its review"));
			}
		}
		child.kill();
		try {
			const candidate = await services.review(parsed.options, call);
			settled ??= {
				value: structuredClone(candidate),
				captureFailures: message.captureFailures,
			};
		} catch (error) {
			settled ??= { failure: { stopReason: reasonOf(error), error: cut(messageOf(error)) } };
		} finally {
			open.delete(id);
			endProgram();
			reviewEnded.resolve(undefined);
		}
	};

	const answerRead = async (
		message: Extract<ChildMessage, { kind: "read" }>,
		call: AbortSignal,
	) => {
		try {
			reply({
				kind: "read-result",
				id: message.id,
				value: await services.reader.answer(message.request, call),
			});
		} catch (error) {
			reply({
				kind: "read-failed",
				id: message.id,
				reason: reasonOf(error),
				message: cut(messageOf(error)),
			});
		}
	};

	const acceptMeta = (value: unknown) => {
		if (meta !== undefined) {
			fail("invalid-program", "meta was sent twice");
			return;
		}
		const parsed = parseMeta(value);
		if ("error" in parsed) {
			fail("invalid-program", parsed.error);
			return;
		}
		if (isPracticeScriptPath(modulePath) && Object.keys(parsed.meta).length > 0) {
			fail(
				"invalid-program",
				"A practice script must declare empty meta. Rewrite the script for this API.",
			);
			return;
		}
		meta = parsed.meta;
		const models = acceptModels(meta, services.models);
		report = models.report;
		accepted = models.available;
		if (models.missing.length > 0) {
			settled ??= {
				failure: {
					stopReason: "unavailable",
					error: `no model is bound for the required slot(s) ${models.missing.join(", ")}`,
					skipped: true,
				},
			};
			child.kill();
			return;
		}
		child.send({
			kind: "meta-accepted",
			models: accepted,
			forcedToolChoice: supportsForcedToolChoice(services.models.chat),
			tokens: services.budget.left,
		} satisfies HostMessage);
	};

	const answerRequest = async (
		message: Extract<ChildMessage, { kind: "model" | "read" | "review" }>,
		request: OpenRequest,
		callSignal: AbortSignal,
	) => {
		try {
			if (message.kind === "model") {
				await answerModel(message, request, callSignal);
			} else if (message.kind === "read") {
				await answerRead(message, callSignal);
			} else {
				await answerReview(message, callSignal);
			}
		} catch (error) {
			fail("error", `the host could not answer a ${message.kind} request: ${messageOf(error)}`);
		} finally {
			outstanding.delete(request);
		}
	};

	child.onMessage((raw) => {
		if (handOff.happened) {
			return;
		}
		if (aborted === undefined && Date.now() >= job.deadline) {
			sendAbort("deadline");
		}
		if (settled !== undefined && aborted === undefined && !finished) {
			return;
		}
		if (typeof raw !== "string") {
			fail("error", "the program's process must send JSON text");
			return;
		}
		if (Buffer.byteLength(raw) > MAX_IPC_MESSAGE_BYTES) {
			fail("error", `the program sent a message larger than ${MAX_IPC_MESSAGE_BYTES} bytes`);
			return;
		}
		let value: unknown;
		try {
			value = JSON.parse(raw);
		} catch {
			fail("error", "the program sent a message that is not JSON");
			return;
		}
		if (finished && isJsonObject(value) && (value.kind === "result" || value.kind === "failed")) {
			settled = {
				failure: { stopReason: "error", error: "the program sent a second final message" },
			};
			child.kill();
			return;
		}
		if (settled !== undefined && aborted === undefined) {
			return;
		}
		const parsed = childMessageSchema.safeParse(value);
		if (!parsed.success) {
			fail("error", "the program's process sent a message the host does not accept");
			return;
		}
		const message = parsed.data;
		switch (message.kind) {
			case "meta": {
				acceptMeta(message.meta);
				return;
			}
			case "log": {
				services.log(`  [${slug}] ${message.text}`);
				return;
			}
			case "cancel": {
				// A cancel may cross the reply it cancels; one for a request already answered changes nothing.
				open
					.get(message.id)
					?.call.abort(new UnratedError("cancelled", "the program cancelled the call"));
				return;
			}
			case "result": {
				if (meta === undefined) {
					fail("invalid-program", "the program sent its result before meta");
					return;
				}
				finished = true;
				if (aborted !== undefined) {
					return;
				}
				settled = { value: message.value, captureFailures: message.captureFailures };
				return;
			}
			case "failed": {
				finished = true;
				const reason = message.reason ?? (meta === undefined ? "invalid-program" : "error");
				settled = {
					failure: {
						stopReason: hostStop ?? reason,
						error: cut(message.message),
					},
				};
				return;
			}
			case "model":
			case "read":
			case "review": {
				if (message.kind !== "read" && meta === undefined) {
					fail("invalid-program", `the program sent a ${message.kind} request before meta`);
					return;
				}
				// Only rising ids pass, because whether the first use is still open depends on scheduling. A
				// program's process does this by counting, and no count of its requests grows the host's memory.
				if (message.id <= lastRequestId) {
					fail("error", `the program reused the id of request ${message.id}`);
					return;
				}
				lastRequestId = message.id;
				if (open.size >= MAX_IN_FLIGHT) {
					fail("error", `more than ${MAX_IN_FLIGHT} requests at once`);
					return;
				}
				if (aborted !== undefined) {
					refuseRequest(message.kind, message.id, aborted);
					return;
				}
				const call = new AbortController();
				const request: OpenRequest = {
					kind: message.kind,
					call,
					...(message.kind === "model" && { slot: message.slot }),
				};
				open.set(message.id, request);
				const callSignal = AbortSignal.any([signal, call.signal]);
				const answering = answerRequest(message, request, callSignal);
				outstanding.set(request, answering);
			}
		}
	});
	child.onError((failure) => {
		// A send to a process that the host just ended raises an error event, which must not fail the review.
		if (!handOff.happened) {
			settled ??= { failure: { stopReason: "error", error: cut(messageOf(failure)) } };
		}
	});
	const deadlineLimit = setTimeout(stopAtLimit, Math.max(0, job.deadline - Date.now()) + RETURN_MS);
	child.send({ kind: "job", job: { ...job, modulePath } } satisfies HostMessage);
	const exitSignal = await processGone.promise;
	if (handOff.happened) {
		await reviewEnded.promise;
	}
	clearTimeout(deadlineLimit);
	clearTimeout(killLater);
	const drainTimer = new AbortController();
	try {
		const drained = await Promise.race([
			Promise.allSettled(outstanding.values()),
			sleep(RETURN_MS, null, { signal: drainTimer.signal }),
		]);
		if (drained === null) {
			throw new ProgramSettlementError("the program's requests did not stop after cancellation");
		}
	} finally {
		drainTimer.abort();
	}
	const done = (outcome: Omit<ProgramExecution, "models" | "calls">): ProgramExecution => ({
		...outcome,
		models: structuredClone(report),
		calls: { ...calls },
	});
	if (settled !== undefined) {
		return done(settled);
	}
	if (limit.reached || aborted !== undefined) {
		return done({
			failure: {
				stopReason: hostStop ?? "deadline",
				error: "the program did not return before its deadline",
			},
		});
	}
	return done({
		failure: {
			stopReason: "error",
			error:
				typeof exitSignal === "string"
					? `the program's process was killed by ${exitSignal}`
					: "the program's process ended without a result",
		},
	});
}
