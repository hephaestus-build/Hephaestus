import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdtempSync, readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { Socket } from "node:net";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import test from "node:test";

import {
	ModelRuntime,
	type SessionEntry,
	type SessionMessageEntry,
} from "@earendil-works/pi-coding-agent";

import {
	FETCH_CONTEXT_PATHS,
	isFetchContextKey,
	isRecord,
	parseCommand,
	type PartialSnapshot,
	type TurnContext,
	type TurnEnd,
} from "../../../main/resources/agent/heph-host-protocol.ts";
import {
	breakpointBeforeSituation,
	createHephHost,
	FETCH_CONTEXT_INDEX,
	LeaseLostError,
	type ModelFactory,
	proxyModels,
	runCommands,
	THINKING_STAGE,
	tooLarge,
	turnStage,
	type TurnApi,
} from "../../../main/resources/agent/heph-host.ts";

// The faux provider ships in pi-ai, which the root package reaches only through pi-coding-agent.
const piAi: unknown = await import(
	new URL("../../pi-ai/dist/index.js", import.meta.resolve("@earendil-works/pi-coding-agent")).href
);

interface FauxRequest {
	messages: unknown[];
}

type FauxStep = (context: FauxRequest, options?: { reasoning?: string }) => unknown;

interface FauxHandle {
	provider: Parameters<ModelRuntime["registerNativeProvider"]>[0];
	getModel: () => { id: string };
	appendResponses: (steps: FauxStep[]) => void;
}

interface FauxModule {
	fauxProvider: (options: {
		provider: string;
		tokensPerSecond?: number;
		models?: { id: string; reasoning?: boolean; contextWindow?: number; maxTokens?: number }[];
	}) => FauxHandle;
	fauxAssistantMessage: (
		content: unknown,
		options?: { stopReason?: string; errorMessage?: string },
	) => unknown;
	fauxText: (text: string) => unknown;
	fauxToolCall: (name: string, args: Record<string, unknown>) => unknown;
}

function isFauxModule(value: unknown): value is FauxModule {
	return (
		typeof value === "object" &&
		value !== null &&
		"fauxProvider" in value &&
		typeof value.fauxProvider === "function" &&
		"fauxAssistantMessage" in value &&
		typeof value.fauxAssistantMessage === "function"
	);
}

if (!isFauxModule(piAi)) {
	throw new Error("pi-ai ships no faux provider");
}
const faux = piAi;

const AGENT_DIR = mkdtempSync(nodePath.join(tmpdir(), "heph-host-agent-"));
const CWD = mkdtempSync(nodePath.join(tmpdir(), "heph-host-cwd-"));
const SITUATION = "<situation>\nNow: Thu 2026-10-08 09:12 UTC.\n</situation>";

function textOf(message: unknown): string {
	if (!isRecord(message)) {
		return "";
	}
	const { content } = message;
	if (typeof content === "string") {
		return content;
	}
	return Array.isArray(content)
		? content
				.map((block: unknown) =>
					isRecord(block) && typeof block.text === "string" ? block.text : "",
				)
				.join("")
		: "";
}

function roleOf(message: unknown): string {
	return isRecord(message) && typeof message.role === "string" ? message.role : "";
}

function idOf(entry: unknown): string {
	return isRecord(entry) && typeof entry.id === "string" ? entry.id : "";
}

/** A faux model whose answers a test scripts per request, streamed at `tokensPerSecond`. */
function fauxModels(
	steps: FauxStep[],
	tokensPerSecond = 100_000,
	reasoning = false,
	limits?: { contextWindow: number; maxTokens: number },
): ModelFactory {
	const provider = `faux-${crypto.randomUUID()}`;
	const handle = faux.fauxProvider({
		provider,
		tokensPerSecond,
		...(reasoning || limits ? { models: [{ id: "faux-model", reasoning, ...limits }] } : {}),
	});
	handle.appendResponses(steps);
	return async () => {
		const modelRuntime = await ModelRuntime.create({
			authPath: `${AGENT_DIR}/auth.json`,
			modelsPath: null,
			allowModelNetwork: false,
		});
		modelRuntime.registerNativeProvider(handle.provider);
		const model = modelRuntime.getModel(provider, handle.getModel().id);
		if (model === undefined) {
			throw new Error("faux model missing");
		}
		return { modelRuntime, model };
	};
}

/** A turn's worker endpoints, and what they received. */
interface FakeWorker {
	/** The entries the turn's end stored. */
	readonly appended: SessionEntry[];
	readonly partials: PartialSnapshot[];
	readonly fetched: string[];
	readonly searched: Record<string, unknown>[];
	api: () => TurnApi;
	waitForEnd: () => Promise<TurnEnd>;
	/** The session as the ledger holds it after the turn: the path it started from and what the turn appended. */
	ledger: () => unknown[];
}

function isSessionEntry(value: unknown): value is SessionEntry {
	return isRecord(value) && typeof value.type === "string" && typeof value.id === "string";
}

function fakeWorker(
	context: TurnContext,
	fetchContext: (resource: string, signal: AbortSignal) => Promise<unknown> = async (resource) => ({
		resource,
	}),
	onPartial?: (snapshot: PartialSnapshot) => void,
): FakeWorker {
	const appended: SessionEntry[] = [];
	const partials: PartialSnapshot[] = [];
	const fetched: string[] = [];
	const searched: Record<string, unknown>[] = [];
	const ended = Promise.withResolvers<TurnEnd>();
	let open = true;
	// Like the worker, which answers 409 once the attempt ended.
	const check = () => {
		if (!open) {
			throw new LeaseLostError("the turn ended");
		}
	};
	return {
		appended,
		partials,
		fetched,
		searched,
		api: () => ({
			context: async () => structuredClone(context),
			fetchContext: async (resource, signal) => {
				fetched.push(resource);
				return fetchContext(resource, signal);
			},
			linkObservation: async () => true,
			search: async (query) => {
				searched.push(query);
				return { hits: [] };
			},
			partial: async (snapshot) => {
				check();
				partials.push(structuredClone(snapshot));
				onPartial?.(snapshot);
			},
			end: async (end) => {
				check();
				open = false;
				appended.push(...structuredClone(end.entries).filter(isSessionEntry));
				ended.resolve(end);
			},
		}),
		waitForEnd: async () => ended.promise,
		ledger: () => [...structuredClone(context.entries), ...structuredClone(appended)],
	};
}

function contextOf(entries: unknown[], prompt: string): TurnContext {
	return {
		sessionId: crypto.randomUUID(),
		entries,
		prompt,
		situation: SITUATION,
		model: {
			apiProtocol: "openai-completions",
			modelId: "faux",
			contextWindow: null,
			maxOutputTokens: null,
			reasoningEffort: null,
		},
	};
}

function hostFor(worker: FakeWorker, models: ModelFactory, partialIntervalMs = 10) {
	return createHephHost({
		cwd: CWD,
		agentDir: AGENT_DIR,
		systemPrompt: "You are Heph.",
		turnApi: () => worker.api(),
		models,
		partialIntervalMs,
	});
}

async function runTurn(
	worker: FakeWorker,
	models: ModelFactory,
	partialIntervalMs = 10,
): Promise<TurnEnd> {
	const host = hostFor(worker, models, partialIntervalMs);
	host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();
	return end;
}

function messages(entries: readonly SessionEntry[], role: string): SessionMessageEntry[] {
	return entries.filter(
		(entry): entry is SessionMessageEntry =>
			entry.type === "message" && entry.message.role === role,
	);
}

void test("a turn sends every entry Pi appended after the stored path once, with its end, the question first and the answer last", async () => {
	const worker = fakeWorker(contextOf([], "[Thu 2026-10-08 09:12 UTC · app] hello"));

	const end = await runTurn(worker, fauxModels([() => faux.fauxAssistantMessage("first answer")]));

	assert.equal(end.outcome, "DONE");
	assert.equal(end.entries.length, worker.appended.length);
	const [question] = messages(worker.appended, "user");
	const answer = messages(worker.appended, "assistant").at(-1);
	assert.equal(textOf(question?.message), "[Thu 2026-10-08 09:12 UTC · app] hello");
	assert.equal(textOf(answer?.message), "first answer");
	assert.ok(
		question !== undefined &&
			answer !== undefined &&
			worker.appended.indexOf(question) < worker.appended.indexOf(answer),
	);

	// The next turn starts from the stored ledger and appends after it, without repeating it.
	const following = fakeWorker(contextOf(worker.ledger(), "again"));
	const next = await runTurn(
		following,
		fauxModels([() => faux.fauxAssistantMessage("second answer")]),
	);
	assert.equal(next.outcome, "DONE");
	const stored = new Set(worker.ledger().map(idOf));
	assert.ok(following.appended.every((entry) => !stored.has(entry.id)));
	assert.deepEqual(
		messages(following.appended, "user").map((entry) => textOf(entry.message)),
		["again"],
	);
});

void test("a turn whose model fails ends failed, sends none of its entries, and tells the worker why", async () => {
	const worker = fakeWorker(contextOf([], "question"));

	const end = await runTurn(
		worker,
		fauxModels([() => faux.fauxAssistantMessage("", { stopReason: "error" })]),
	);

	assert.deepEqual(end, {
		outcome: "FAILED",
		entries: [],
		reason: "ERROR",
		detail: "the model's answer ended with error",
	});
});

void test("a model call that Pi retries leaves its cut-off text out of the end and the partial answer", async () => {
	const worker = fakeWorker(contextOf([], "question"));

	const end = await runTurn(
		worker,
		fauxModels(
			[
				() =>
					faux.fauxAssistantMessage("Here is the first half of my ans", {
						stopReason: "error",
						errorMessage: "terminated",
					}),
				() => faux.fauxAssistantMessage(`Here is the whole answer. ${"More of it. ".repeat(20)}`),
			],
			// Slow enough that the partial answer is reported while the retried answer streams.
			500,
		),
	);

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(
		messages(worker.appended, "assistant").map((entry) => textOf(entry.message)),
		[`Here is the whole answer. ${"More of it. ".repeat(20)}`],
	);
	assert.ok(worker.appended.every((entry) => entry.type !== "context_edit"));
	const answering = worker.partials.filter((snapshot) =>
		JSON.stringify(snapshot).includes("whole answer"),
	);
	assert.ok(answering.length > 0);
	assert.ok(answering.every((snapshot) => !JSON.stringify(snapshot).includes("first half")));
});

void test("the situation reaches every model request last and is never stored", async () => {
	const requests: FauxRequest[] = [];
	// The tool returns once the worker heard that it runs, so the test sees that partial.
	const toolReported = Promise.withResolvers<undefined>();
	const worker = fakeWorker(
		contextOf([], "question"),
		async (resource) => {
			await toolReported.promise;
			return { resource };
		},
		(snapshot) => {
			if (snapshot.activity === "tool" && snapshot.tool === "fetch_context") {
				toolReported.resolve(undefined);
			}
		},
	);
	const models = fauxModels([
		(context) => {
			requests.push(context);
			return faux.fauxAssistantMessage(
				[
					faux.fauxText("let me look"),
					faux.fauxToolCall("fetch_context", { path: "inputs/context/user.json" }),
				],
				{ stopReason: "toolUse" },
			);
		},
		(context) => {
			requests.push(context);
			return faux.fauxAssistantMessage("done");
		},
	]);

	const end = await runTurn(worker, models);

	assert.equal(end.outcome, "DONE");
	assert.equal(requests.length, 2);
	for (const request of requests) {
		assert.ok(textOf(request.messages.at(-1)).startsWith("<situation>"));
	}
	assert.equal(JSON.stringify(worker.appended).includes("<situation>"), false);
	assert.deepEqual(worker.fetched, ["inputs/context/user.json"]);
	// The answer in progress keeps what Heph wrote before the tool, though nothing is stored until the end. It carries
	// the text and nothing else the worker does not read.
	const tool = worker.partials.find(
		(partial) => partial.activity === "tool" && partial.tool === "fetch_context",
	);
	assert.deepEqual(tool?.messages, [{ content: [{ type: "text", text: "let me look" }] }]);
});

void test("search_conversation asks the worker in the shape the worker binds", async () => {
	// HephTurnControllerIntegrationTest posts the same file to the real endpoint.
	const query: unknown = JSON.parse(
		readFileSync(new URL("heph-turn-contract/search.request.json", import.meta.url), "utf8"),
	);
	assert.ok(isRecord(query));
	const worker = fakeWorker(contextOf([], "what did we decide?"));

	const end = await runTurn(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage([faux.fauxToolCall("search_conversation", query)], {
					stopReason: "toolUse",
				}),
			() => faux.fauxAssistantMessage("found nothing"),
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.deepEqual(worker.searched, [query]);
});

void test("after a completed turn only its own fetch_context results stay verbatim", async () => {
	const fetchTurn = async (resource: string, entries: unknown[], prompt: string) => {
		const worker = fakeWorker(contextOf(entries, prompt));
		const end = await runTurn(
			worker,
			fauxModels([
				() =>
					faux.fauxAssistantMessage([faux.fauxToolCall("fetch_context", { path: resource })], {
						stopReason: "toolUse",
					}),
				() => faux.fauxAssistantMessage(`read ${resource}`),
			]),
		);
		assert.equal(end.outcome, "DONE");
		return worker;
	};
	const first = await fetchTurn("inputs/context/user.json", [], "one");
	const second = await fetchTurn("inputs/context/workspace.json", first.ledger(), "two");

	const [firstResult] = messages(first.appended, "toolResult");
	const edits = second.appended.flatMap((entry) => (entry.type === "context_edit" ? [entry] : []));
	assert.deepEqual(
		edits.map((entry) => entry.targetId),
		[firstResult?.id],
	);
	assert.match(
		JSON.stringify(edits[0]),
		/fetch_context inputs\/context\/user\.json, read at .*fetch it again/u,
	);

	// The third turn's model sees the first result as a note and the second verbatim.
	const requests: FauxRequest[] = [];
	const third = fakeWorker(contextOf(second.ledger(), "three"));
	const end = await runTurn(
		third,
		fauxModels([
			(context) => {
				requests.push(context);
				return faux.fauxAssistantMessage("three");
			},
		]),
	);
	assert.equal(end.outcome, "DONE");
	const results = requests[0]?.messages.filter((message) => roleOf(message) === "toolResult") ?? [];
	assert.equal(results.length, 2);
	assert.match(textOf(results[0]), /^\[fetch_context inputs\/context\/user\.json, read at/u);
	assert.match(textOf(results[1]), /inputs\/context\/workspace\.json/u);
	assert.doesNotMatch(textOf(results[1]), /read at/u);
});

const NO_USAGE = {
	input: 0,
	output: 0,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 0,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

/** One stored question and answer. `usage` is what the provider reported for the answer. */
function storedExchange(index: number, parentId: string | null, usage = NO_USAGE): unknown[] {
	const question = crypto.randomUUID().slice(0, 8);
	const answer = crypto.randomUUID().slice(0, 8);
	const timestamp = new Date(0).toISOString();
	return [
		{
			type: "message",
			id: question,
			parentId,
			timestamp,
			message: { role: "user", content: `question ${index} ${"q".repeat(4500)}`, timestamp: 0 },
		},
		{
			type: "message",
			id: answer,
			parentId: question,
			timestamp,
			message: {
				role: "assistant",
				content: [{ type: "text", text: `answer ${index} ${"a".repeat(4500)}` }],
				api: "openai-completions",
				provider: "faux",
				model: "faux",
				usage,
				stopReason: "stop",
				timestamp: 0,
			},
		},
	];
}

/** Whether a model request is one of Pi's summary requests rather than a turn of the conversation. */
function asksForSummary(request: FauxRequest): boolean {
	const text = request.messages.map(textOf).join("\n");
	return text.includes("<conversation>") || text.includes("# Conversation");
}

/**
 * A stored conversation past the working trigger. The last answer reports 40,000 tokens, below the working trigger
 * (MENTOR_WORKING_TOKENS). A question stored after it, from a turn that failed, adds about 10,000 more by Pi's
 * estimate of one token per four characters. Pi's own check before a prompt reads only the last answer's usage; the
 * host's counts both and compacts.
 */
function pastTheTrigger(): unknown[] {
	const earlier: unknown[] = [];
	for (let index = 0; index < 25; index += 1) {
		const parent = idOf(earlier.at(-1));
		const tokens = index === 24 ? 40_000 : 0;
		earlier.push(
			...storedExchange(index, parent === "" ? null : parent, {
				...NO_USAGE,
				input: tokens,
				totalTokens: tokens,
			}),
		);
	}
	earlier.push({
		type: "message",
		id: crypto.randomUUID().slice(0, 8),
		parentId: idOf(earlier.at(-1)),
		timestamp: new Date(0).toISOString(),
		message: { role: "user", content: `unanswered ${"u".repeat(40_000)}`, timestamp: 0 },
	});
	return earlier;
}

void test("a conversation past the working budget is compacted before the prompt, and the end carries the summary before the question", async () => {
	const earlier = pastTheTrigger();
	const answered: FauxRequest[] = [];
	// Pi may summarise a split turn's prefix in a call of its own, so every step answers what it is asked.
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		answered.push(request);
		return faux.fauxAssistantMessage("the answer");
	};
	const worker = fakeWorker(contextOf(earlier, "the question"));

	const end = await runTurn(worker, fauxModels([step, step, step]));

	assert.equal(end.outcome, "DONE");
	const summary = worker.appended.findIndex((entry) => entry.type === "compaction");
	const question = worker.appended.findIndex(
		(entry) => entry.type === "message" && textOf(entry.message) === "the question",
	);
	const answer = worker.appended.findIndex(
		(entry) => entry.type === "message" && textOf(entry.message) === "the answer",
	);
	assert.notEqual(summary, -1, "the conversation was compacted");
	assert.ok(summary < question, "the summary precedes the question");
	assert.ok(question < answer, "the answer follows the question");
	// The question was answered from the summary, not from the conversation it replaced.
	assert.equal(answered.length, 1);
	const sent = answered[0]?.messages.map(textOf).join("\n") ?? "";
	assert.match(sent, /the summary/u);
	assert.doesNotMatch(sent, /question 0 /u);
});

void test("a summary is written with thinking off and the answer with the configured effort, and no level is stored", async () => {
	const levels: { summary: (string | undefined)[]; answer: (string | undefined)[] } = {
		summary: [],
		answer: [],
	};
	const step: FauxStep = (request, options) => {
		if (asksForSummary(request)) {
			levels.summary.push(options?.reasoning);
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		levels.answer.push(options?.reasoning);
		return faux.fauxAssistantMessage("the answer");
	};
	const context = contextOf(pastTheTrigger(), "the question");
	context.model.reasoningEffort = "HIGH";
	const worker = fakeWorker(context);

	const end = await runTurn(worker, fauxModels([step, step, step], 100_000, true));

	assert.equal(end.outcome, "DONE");
	assert.ok(levels.summary.length > 0, "the conversation was compacted");
	assert.deepEqual(new Set(levels.summary), new Set([undefined]));
	assert.deepEqual(levels.answer, ["high"]);
	assert.equal(
		worker.appended.some(
			(entry) => entry.type === "thinking_level_change" && entry.thinkingLevel === "off",
		),
		false,
		"the summary's level is not stored in the conversation",
	);
});

void test("a conversation on the smallest window Heph takes is shortened, keeps its summary and answers every later turn", async () => {
	// A 32,768-token model with a fixed prompt of about 6,000 tokens. The newest answer alone is about 17,000 tokens,
	// so Pi keeps it whole and the shortened conversation stays past a fixed working budget; the model still has room.
	const earlier: unknown[] = [];
	for (let index = 0; index < 6; index += 1) {
		const parent = idOf(earlier.at(-1));
		earlier.push(...storedExchange(index, parent === "" ? null : parent));
	}
	const long = storedExchange(6, idOf(earlier.at(-1)), {
		...NO_USAGE,
		input: 31_000,
		totalTokens: 31_000,
	});
	const longAnswer = long[1];
	if (isRecord(longAnswer) && isRecord(longAnswer.message)) {
		longAnswer.message.content = [{ type: "text", text: `answer 6 ${"a".repeat(68_000)}` }];
	}
	earlier.push(...long);
	const summaries: FauxRequest[] = [];
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			summaries.push(request);
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		return faux.fauxAssistantMessage("the answer");
	};
	const limits = { contextWindow: 32_768, maxTokens: 8192 };
	const runAt32k = async (worker: FakeWorker) => {
		const host = createHephHost({
			cwd: CWD,
			agentDir: AGENT_DIR,
			systemPrompt: `You are Heph. ${"p".repeat(24_000)}`,
			turnApi: () => worker.api(),
			models: fauxModels([step, step, step, step], 100_000, false, limits),
			partialIntervalMs: 10,
		});
		host.start(crypto.randomUUID(), 1, "turn-token");
		const end = await worker.waitForEnd();
		await host.idle();
		return end;
	};

	const first = fakeWorker(contextOf(earlier, "the question"));
	const end = await runAt32k(first);

	assert.equal(end.outcome, "DONE", end.detail ?? "");
	assert.ok(summaries.length > 0, "the conversation was summarised");
	assert.ok(
		first.appended.some((entry) => entry.type === "compaction"),
		"the summary is stored",
	);
	for (const question of ["the next question", "and one more"]) {
		const next = fakeWorker(contextOf(first.ledger(), question));
		const later = await runAt32k(next);
		assert.equal(later.outcome, "DONE", later.detail ?? "");
		first.appended.push(...next.appended);
	}
});

void test("a summary written before a turn fails is kept, so the next turn starts from it", async () => {
	let summaries = 0;
	let failAnswer = true;
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			summaries += 1;
			return faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		return faux.fauxAssistantMessage(failAnswer ? "  " : "the answer");
	};
	const worker = fakeWorker(contextOf(pastTheTrigger(), "the question"));

	const end = await runTurn(worker, fauxModels([step, step, step]));

	assert.equal(end.outcome, "FAILED");
	assert.deepEqual(
		end.entries.map((entry) => (isRecord(entry) ? entry.type : "")),
		["compaction"],
		"only the summary is stored",
	);
	const written = summaries;

	failAnswer = false;
	const next = fakeWorker(contextOf(worker.ledger(), "the question again"));
	const later = await runTurn(next, fauxModels([step, step, step]));

	assert.equal(later.outcome, "DONE");
	assert.equal(summaries, written, "the next turn does not summarise again");
});

void test("a summary that fails leaves the history whole and is answered, and the next turn compacts again", async () => {
	let failSummaries = true;
	const step: FauxStep = (request) => {
		if (asksForSummary(request)) {
			return failSummaries
				? faux.fauxAssistantMessage("## Goal\nthe sum", { stopReason: "length" })
				: faux.fauxAssistantMessage("## Goal\nthe summary");
		}
		return faux.fauxAssistantMessage("the answer");
	};
	const worker = fakeWorker(contextOf(pastTheTrigger(), "the question"));

	const end = await runTurn(worker, fauxModels([step, step, step, step, step]));

	assert.equal(end.outcome, "DONE");
	assert.equal(worker.appended.filter((entry) => entry.type === "compaction").length, 0);
	assert.ok(
		worker.appended.some(
			(entry) => entry.type === "message" && textOf(entry.message) === "the answer",
		),
	);

	failSummaries = false;
	const next = fakeWorker(contextOf(worker.ledger(), "the next question"));
	const later = await runTurn(next, fauxModels([step, step, step, step, step]));

	assert.equal(later.outcome, "DONE");
	assert.ok(
		next.appended.some((entry) => entry.type === "compaction"),
		"the next turn compacts the conversation",
	);
});

void test("a conversation over the working budget with nothing older to summarise is answered, not failed", async () => {
	// One short exchange whose answer reports more than the working trigger: Pi keeps it whole as recent and has
	// nothing to compact. Each later question would meet the same history, so the turn must go ahead.
	const earlier = storedExchange(0, null, { ...NO_USAGE, input: 60_000, totalTokens: 60_000 });
	const answered: FauxRequest[] = [];
	const worker = fakeWorker(contextOf(earlier, "the question"));

	const end = await runTurn(
		worker,
		fauxModels([
			(request) => {
				answered.push(request);
				return faux.fauxAssistantMessage("the answer");
			},
		]),
	);

	assert.equal(end.outcome, "DONE");
	assert.equal(answered.length, 1);
	assert.equal(worker.appended.filter((entry) => entry.type === "compaction").length, 0);
	assert.ok(
		worker.appended.some(
			(entry) => entry.type === "message" && textOf(entry.message) === "the answer",
		),
		"the answer is stored",
	);
});

void test("a conversation past the model's window that cannot get shorter fails without asking the model", async () => {
	// Asked anyway, the model would answer and Pi would read the answer as an overflow and leave it out, so the turn
	// would end done with nothing to show.
	const earlier = storedExchange(0, null, { ...NO_USAGE, input: 200_000, totalTokens: 200_000 });
	const answered: FauxRequest[] = [];
	const worker = fakeWorker(contextOf(earlier, "the question"));

	const end = await runTurn(
		worker,
		fauxModels([
			(request) => {
				answered.push(request);
				return faux.fauxAssistantMessage("the answer");
			},
		]),
	);

	assert.equal(end.outcome, "FAILED");
	assert.match(end.detail ?? "", /does not fit the model's context window/u);
	assert.equal(answered.length, 0);
});

void test("an answer with no words fails the turn instead of ending it done with nothing to show", async () => {
	const worker = fakeWorker(contextOf([], "the question"));

	const end = await runTurn(worker, fauxModels([() => faux.fauxAssistantMessage("  ")]));

	assert.equal(end.outcome, "FAILED");
	assert.equal(end.reason, "ERROR");
});

void test("an answer of only chat-template control tokens fails the turn and stores no answer", async () => {
	const worker = fakeWorker(contextOf([], "the question"));

	const end = await runTurn(
		worker,
		fauxModels([() => faux.fauxAssistantMessage("<|channel|> <|message|>\n")]),
	);

	assert.equal(end.outcome, "FAILED");
	assert.equal(end.reason, "ERROR");
	assert.deepEqual(end.entries, []);
});

void test("a fetch in flight names the stage of what it reads, in the developer's words", async () => {
	const toolReported = Promise.withResolvers<undefined>();
	const worker = fakeWorker(
		contextOf([], "is my pull request ready?"),
		async (resource) => {
			await toolReported.promise;
			return { resource };
		},
		(snapshot) => {
			if (snapshot.activity === "tool") {
				toolReported.resolve(undefined);
			}
		},
	);

	const end = await runTurn(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("fetch_context", { path: "inputs/context/merge_readiness/42.json" })],
					{ stopReason: "toolUse" },
				),
			() => faux.fauxAssistantMessage("It is ready."),
		]),
	);

	assert.equal(end.outcome, "DONE");
	const tool = worker.partials.find((partial) => partial.activity === "tool");
	assert.equal(tool?.stage, "Reading your pull requests and issues…");
});

void test("a fast read's stage reaches the worker though Heph thinks again within the same window", async () => {
	// The read returns at once and the model's next answer streams slowly: without holding the tool's stage, the
	// window's snapshot would carry only "Thinking…".
	const worker = fakeWorker(contextOf([], "what did the review say?"));

	const end = await runTurn(
		worker,
		fauxModels(
			[
				() =>
					faux.fauxAssistantMessage(
						[
							faux.fauxToolCall("fetch_context", {
								path: "inputs/context/observations_history.json",
							}),
						],
						{ stopReason: "toolUse" },
					),
				() => faux.fauxAssistantMessage("It found two points to work on. ".repeat(20)),
			],
			400,
		),
		250,
	);

	assert.equal(end.outcome, "DONE");
	assert.ok(
		worker.partials.some((partial) => partial.stage === "Reading your practice feedback…"),
		JSON.stringify(worker.partials.map((partial) => partial.stage)),
	);
});

function fetching(path: string): string {
	return turnStage("fetch_context", { path });
}

void test("every context document and tool has one stage label, and anything else is thinking", () => {
	assert.equal(
		fetching("inputs/context/recent_authored_work.json"),
		"Reading your pull requests and issues…",
	);
	assert.equal(fetching("inputs/context/issue/77.json"), "Reading your pull requests and issues…");
	assert.equal(fetching("inputs/context/activity.json"), "Reading your pull requests and issues…");
	assert.equal(
		fetching("inputs/context/observations_history/0b6c1f0e-8d3a-4c51-9a4e-2f7d5c9e1a33.json"),
		"Reading your practice feedback…",
	);
	assert.equal(
		fetching("inputs/context/review_attempts/issue/21.json"),
		"Reading your practice feedback…",
	);
	assert.equal(fetching("inputs/context/practice_profile.json"), "Reading your practice feedback…");
	assert.equal(fetching("inputs/context/weekly_digest.json"), "Reading your practice feedback…");
	assert.equal(fetching("inputs/context/slack_conversations.json"), "Reading your team's Slack…");
	assert.equal(fetching("inputs/context/outline_docs.json"), "Reading your team's docs…");
	assert.equal(
		turnStage("search_conversation", { query: "x" }),
		"Looking back through this conversation…",
	);
	assert.equal(turnStage("link_observation", {}), THINKING_STAGE);
	assert.equal(fetching("../etc/passwd"), THINKING_STAGE);
	for (const path of FETCH_CONTEXT_PATHS) {
		assert.notEqual(fetching(path), THINKING_STAGE, path);
	}
});

void test("a stop while a tool runs ends the turn stopped, and Pi answers every open tool call on the next request", async () => {
	const toolStarted = Promise.withResolvers<undefined>();
	const worker = fakeWorker(contextOf([], "question"), async (_resource, signal) => {
		toolStarted.resolve(undefined);
		const aborted = Promise.withResolvers<undefined>();
		signal.addEventListener("abort", () => aborted.reject(new Error("stopped")));
		return aborted.promise;
	});
	const host = hostFor(
		worker,
		fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("fetch_context", { path: "inputs/context/user.json" })],
					{
						stopReason: "toolUse",
					},
				),
			() => faux.fauxAssistantMessage("never"),
		]),
	);
	const turnId = crypto.randomUUID();
	host.start(turnId, 1, "turn-token");
	await toolStarted.promise;
	host.stop(turnId, 1, "developer");
	const end = await worker.waitForEnd();
	await host.idle();
	assert.equal(end.outcome, "STOPPED");
	const calls = messages(worker.appended, "assistant").flatMap((entry) =>
		entry.message.role === "assistant"
			? entry.message.content.filter((block) => block.type === "toolCall")
			: [],
	);
	assert.notEqual(calls.length, 0);

	// The next turn reads the stopped one cut after its tool calls, the worst a stop can leave. A provider refuses a
	// tool call without its result; Pi's transformMessages adds one to the request.
	const requests: unknown[] = [];
	const proxy = await fakeProxy(async (_request, body) => {
		requests.push(JSON.parse(body));
		return "after the stop";
	});
	try {
		const ledger = worker.ledger();
		const calling = ledger.findIndex(
			(entry) => isRecord(entry) && isRecord(entry.message) && entry.message.role === "assistant",
		);
		const open = ledger.slice(0, calling + 1);
		const following = fakeWorker(contextOf(open, "go on"));
		const next = await runTurn(following, proxyModels(proxy.url, AGENT_DIR));
		assert.equal(next.outcome, "DONE");
	} finally {
		proxy.close();
	}
	const [request] = requests;
	const sent = isRecord(request) && Array.isArray(request.messages) ? request.messages : [];
	const answered = new Set(
		sent.flatMap((message: unknown) =>
			isRecord(message) && message.role === "tool" && typeof message.tool_call_id === "string"
				? [message.tool_call_id]
				: [],
		),
	);
	for (const call of calls) {
		assert.ok(answered.has(call.id), `the request answers tool call ${call.id}`);
	}
});

void test("a stop before the session exists ends the turn stopped without a model call", async () => {
	let calls = 0;
	const worker = fakeWorker(contextOf([], "question"));
	const contextAsked = Promise.withResolvers<undefined>();
	const stopSent = Promise.withResolvers<undefined>();
	const api = worker.api();
	const host = createHephHost({
		cwd: CWD,
		agentDir: AGENT_DIR,
		systemPrompt: "You are Heph.",
		turnApi: () => ({
			...api,
			context: async () => {
				contextAsked.resolve(undefined);
				await stopSent.promise;
				return api.context();
			},
		}),
		models: fauxModels([
			() => {
				calls += 1;
				return faux.fauxAssistantMessage("never");
			},
		]),
	});
	const turnId = crypto.randomUUID();
	host.start(turnId, 1, "turn-token");
	await contextAsked.promise;
	host.stop(turnId, 1, "developer");
	stopSent.resolve(undefined);
	const end = await worker.waitForEnd();
	await host.idle();
	assert.equal(end.outcome, "STOPPED");
	assert.equal(calls, 0);
});

function completionChunk(delta: Record<string, unknown>, finish: string | null): string {
	const chunk = { id: "c", object: "chat.completion.chunk", created: 1, model: "m" };
	return `data: ${JSON.stringify({ ...chunk, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
}

/** An OpenAI-compatible endpoint standing in for the worker's LLM proxy. */
async function fakeProxy(answer: (request: IncomingMessage, body: string) => Promise<string>) {
	const server = createServer((request, response) => {
		let body = "";
		request.on("data", (chunk: Buffer) => {
			body += chunk.toString("utf8");
		});
		request.on("end", () => {
			void (async () => {
				const text = await answer(request, body);
				response.writeHead(200, { "content-type": "text/event-stream" });
				response.write(completionChunk({ role: "assistant", content: text }, null));
				response.write(completionChunk({}, "stop"));
				response.end("data: [DONE]\n\n");
			})();
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("no port");
	}
	return { url: `http://127.0.0.1:${address.port}`, close: () => server.close() };
}

void test("two conversations run at once, each with its own credential on its own model calls", async () => {
	const inFlight = { now: 0, most: 0 };
	// Each model call answers once both are in flight, which only turns running at once can reach.
	const bothAsked = Promise.withResolvers<undefined>();
	const seen: string[] = [];
	const proxy = await fakeProxy(async (request, body) => {
		inFlight.now += 1;
		inFlight.most = Math.max(inFlight.most, inFlight.now);
		if (inFlight.now === 2) {
			bothAsked.resolve(undefined);
		}
		await bothAsked.promise;
		inFlight.now -= 1;
		const conversation = body.includes("main question") ? "main" : "thread";
		seen.push(`${conversation}:${request.headers.authorization ?? ""}`);
		return `answer for ${conversation}`;
	});
	try {
		const main = fakeWorker(contextOf([], "main question"));
		const thread = fakeWorker(contextOf([], "thread question"));
		const host = createHephHost({
			cwd: CWD,
			agentDir: AGENT_DIR,
			systemPrompt: "You are Heph.",
			turnApi: (turnId) => (turnId === "main" ? main : thread).api(),
			models: proxyModels(proxy.url, AGENT_DIR),
		});
		host.start("main", 1, "token-main");
		host.start("thread", 1, "token-thread");
		assert.equal(host.running, 2);
		const ends = await Promise.all([main.waitForEnd(), thread.waitForEnd()]);
		await host.idle();

		assert.deepEqual(
			ends.map((end) => end.outcome),
			["DONE", "DONE"],
		);
		assert.equal(inFlight.most, 2, "both turns waited on their model at the same time");
		assert.deepEqual(seen.toSorted(), ["main:Bearer token-main", "thread:Bearer token-thread"]);
		assert.equal(textOf(messages(main.appended, "assistant").at(-1)?.message), "answer for main");
		assert.equal(
			textOf(messages(thread.appended, "assistant").at(-1)?.message),
			"answer for thread",
		);
	} finally {
		proxy.close();
	}
});

void test("a summary asks for thinking off through the standard control only, with no provider-specific field", async () => {
	const bodies: { summary: Record<string, unknown>[]; answer: Record<string, unknown>[] } = {
		summary: [],
		answer: [],
	};
	const proxy = await fakeProxy(async (_request, body) => {
		const parsed: unknown = JSON.parse(body);
		const request = isRecord(parsed) ? parsed : {};
		const summary = body.includes("<conversation>") || body.includes("# Conversation");
		(summary ? bodies.summary : bodies.answer).push(request);
		return summary ? "## Goal\nthe summary" : "the answer";
	});
	try {
		const context = contextOf(pastTheTrigger(), "the question");
		context.model.reasoningEffort = "HIGH";
		const worker = fakeWorker(context);
		const host = createHephHost({
			cwd: CWD,
			agentDir: AGENT_DIR,
			systemPrompt: "You are Heph.",
			turnApi: () => worker.api(),
			models: proxyModels(proxy.url, AGENT_DIR),
		});
		host.start(crypto.randomUUID(), 1, "turn-token");
		const end = await worker.waitForEnd();
		await host.idle();

		assert.equal(end.outcome, "DONE");
		assert.ok(bodies.summary.length > 0, "the conversation was summarised");
		for (const summary of bodies.summary) {
			// Thinking off: no effort at all, and no field a single provider's template reads.
			for (const field of [
				"reasoning_effort",
				"enable_thinking",
				"chat_template_kwargs",
				"thinking",
			]) {
				assert.equal(field in summary, false, field);
			}
		}
		assert.equal(bodies.answer.length, 1);
		const answer = bodies.answer.at(0);
		assert.ok(answer);
		assert.equal(answer.reasoning_effort, "high", "the answer keeps the configured effort");
		assert.equal("chat_template_kwargs" in answer, false);
	} finally {
		proxy.close();
	}
});

/** A stand-in for the worker's LLM proxy that answers every model call with `answer`. */
async function proxyAnswering(answer: (response: ServerResponse) => void) {
	const server = createServer((request, response) => {
		request.resume();
		request.on("end", () => answer(response));
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("no port");
	}
	return { url: `http://127.0.0.1:${address.port}`, close: () => server.close() };
}

async function turnAgainst(proxyUrl: string): Promise<TurnEnd> {
	const worker = fakeWorker(contextOf([], "Is the release ready?"));
	const host = createHephHost({
		cwd: CWD,
		agentDir: AGENT_DIR,
		systemPrompt: "You are Heph.",
		turnApi: () => worker.api(),
		models: proxyModels(proxyUrl, AGENT_DIR),
	});
	host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();
	return end;
}

const STALL_ERROR = JSON.stringify({
	error: {
		message: "upstream_stalled: the model provider sent nothing for 120 seconds",
		type: "upstream_stalled",
		code: "upstream_stalled",
	},
});

void test("a turn fails as stalled at once when the proxy ends its stream with a stall error", async () => {
	let calls = 0;
	const proxy = await proxyAnswering((response) => {
		calls += 1;
		response.writeHead(200, { "content-type": "text/event-stream" });
		response.write(completionChunk({ role: "assistant", content: "Let me" }, null));
		response.end(`data: ${STALL_ERROR}\n\n`);
	});
	try {
		const end = await turnAgainst(proxy.url);

		assert.equal(end.outcome, "FAILED");
		assert.equal(end.reason, "STALLED");
		assert.deepEqual(end.entries, []);
		assert.equal(calls, 1, "a stall is not retried");
		assert.match(
			end.detail ?? "",
			/sent nothing for 120 seconds/u,
			"the proxy's words reach the worker log",
		);
	} finally {
		proxy.close();
	}
});

void test("a turn fails as stalled when the proxy answers a call with a stall or a paused provider", async () => {
	for (const code of ["upstream_stalled", "upstream_paused"]) {
		const proxy = await proxyAnswering((response) => {
			response.writeHead(code === "upstream_stalled" ? 504 : 503, {
				"content-type": "application/json",
			});
			response.end(
				JSON.stringify({ error: { message: `${code}: provider unavailable`, type: code, code } }),
			);
		});
		try {
			const end = await turnAgainst(proxy.url);

			assert.equal(end.outcome, "FAILED", code);
			assert.equal(end.reason, "STALLED", code);
		} finally {
			proxy.close();
		}
	}
});

void test("the cache breakpoint moves from the situation to the end of the stored conversation", () => {
	const payload = {
		messages: [
			{ role: "user", content: [{ type: "text", text: "question" }] },
			{
				role: "user",
				content: [{ type: "text", text: SITUATION, cache_control: { type: "ephemeral" } }],
			},
		],
	};
	assert.equal(breakpointBeforeSituation(payload), payload);
	assert.deepEqual(payload.messages[0]?.content[0], {
		type: "text",
		text: "question",
		cache_control: { type: "ephemeral" },
	});
	assert.equal("cache_control" in (payload.messages[1]?.content[0] ?? {}), false);
	assert.equal(
		breakpointBeforeSituation({ messages: [{ role: "user", content: "no situation" }] }),
		undefined,
	);
});

void test("the fetch_context description says what every fixed path holds", () => {
	assert.deepEqual(
		Object.keys(FETCH_CONTEXT_INDEX).toSorted(),
		[...FETCH_CONTEXT_PATHS].toSorted(),
	);
	for (const path of FETCH_CONTEXT_PATHS) {
		assert.ok((FETCH_CONTEXT_INDEX[path] ?? "").length > 0, path);
	}
});

void test("an issue detail is a context resource only in its canonical shape", () => {
	assert.ok(isFetchContextKey("inputs/context/issue/42.json"));
	assert.ok(!isFetchContextKey("inputs/context/issue/../user.json"));
	assert.ok(!isFetchContextKey("inputs/context/issue/1234567890123456789.json"));
	assert.ok(isFetchContextKey("inputs/context/activity.json"));
	assert.ok(isFetchContextKey("inputs/context/practice_profile.json"));
});

void test("only well-formed commands are acted on", () => {
	assert.deepEqual(parseCommand('{"type":"start","turnId":"t","attempt":1,"token":"k"}'), {
		type: "start",
		turnId: "t",
		attempt: 1,
		token: "k",
	});
	assert.deepEqual(parseCommand('{"type":"stop","turnId":"t","attempt":2,"reason":"developer"}'), {
		type: "stop",
		turnId: "t",
		attempt: 2,
		reason: "developer",
	});
	assert.equal(parseCommand('{"type":"stop","turnId":"t","attempt":1,"reason":"bored"}'), null);
	assert.equal(parseCommand('{"type":"start","turnId":"t","attempt":1}'), null);
	assert.equal(parseCommand('{"type":"start","turnId":"t","token":"k"}'), null);
	assert.equal(parseCommand("not json"), null);
});

/** A fake worker whose `fetch_context` waits until the turn is stopped, and the host running one turn on it. */
async function turnInATool(worker?: FakeWorker) {
	const toolStarted = Promise.withResolvers<undefined>();
	const blocked =
		worker ??
		fakeWorker(contextOf([], "question"), async (_resource, signal) => {
			toolStarted.resolve(undefined);
			const aborted = Promise.withResolvers<undefined>();
			signal.addEventListener("abort", () => aborted.reject(new Error("stopped")));
			return aborted.promise;
		});
	let ends = 0;
	const host = createHephHost({
		cwd: CWD,
		agentDir: AGENT_DIR,
		systemPrompt: "You are Heph.",
		turnApi: () => {
			const api = blocked.api();
			return {
				...api,
				end: async (end) => {
					ends += 1;
					await api.end(end);
				},
			};
		},
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("fetch_context", { path: "inputs/context/user.json" })],
					{ stopReason: "toolUse" },
				),
		]),
	});
	const turnId = crypto.randomUUID();
	host.start(turnId, 1, "turn-token");
	await toolStarted.promise;
	return { host, turnId, worker: blocked, ends: () => ends };
}

void test("a stop at the deadline ends the turn failed and sends none of its entries", async () => {
	const { host, turnId, worker } = await turnInATool();

	host.stop(turnId, 1, "deadline");
	const end = await worker.waitForEnd();
	await host.idle();

	assert.deepEqual(end, {
		outcome: "FAILED",
		entries: [],
		reason: "DEADLINE",
		detail: "the worker stopped the turn at its deadline",
	});
});

void test("a stop for a lost lease ends the turn without a word to the worker", async () => {
	const { host, turnId, ends } = await turnInATool();

	host.stop(turnId, 2, "deadline");
	assert.equal(host.running, 1, "a stop for another attempt does not stop this one");
	host.stop(turnId, 1, "lease");
	await host.idle();

	assert.equal(host.running, 0);
	assert.equal(ends(), 0);
});

void test("a later attempt of a running turn stops the earlier one and then runs with its own credential", async () => {
	const toolStarted = Promise.withResolvers<undefined>();
	const first = fakeWorker(contextOf([], "question"), async (_resource, signal) => {
		toolStarted.resolve(undefined);
		const aborted = Promise.withResolvers<undefined>();
		signal.addEventListener("abort", () => aborted.reject(new Error("stopped")));
		return aborted.promise;
	});
	const second = fakeWorker(contextOf([], "question"));
	const credentials: string[] = [];
	let firstEnds = 0;
	const host = createHephHost({
		cwd: CWD,
		agentDir: AGENT_DIR,
		systemPrompt: "You are Heph.",
		turnApi: (_turnId, token) => {
			credentials.push(token);
			const api = (token === "attempt-1" ? first : second).api();
			return token === "attempt-1"
				? {
						...api,
						end: async (end) => {
							firstEnds += 1;
							await api.end(end);
						},
					}
				: api;
		},
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("fetch_context", { path: "inputs/context/user.json" })],
					{ stopReason: "toolUse" },
				),
			() => faux.fauxAssistantMessage("the retried answer"),
		]),
	});
	const turnId = crypto.randomUUID();
	host.start(turnId, 1, "attempt-1");
	await toolStarted.promise;

	host.start(turnId, 2, "attempt-2");
	host.start(turnId, 1, "attempt-1");
	const end = await second.waitForEnd();
	await host.idle();

	assert.deepEqual(credentials, ["attempt-1", "attempt-2"]);
	assert.equal(firstEnds, 0, "the lost attempt ends nothing");
	assert.equal(end.outcome, "DONE");
	assert.equal(
		textOf(messages(second.appended, "assistant").at(-1)?.message),
		"the retried answer",
	);
});

void test("an end the worker refuses for its size is sent again with the documents read as notes", async () => {
	const worker = fakeWorker(contextOf([], "question"));
	const refused: TurnEnd[] = [];
	const host = createHephHost({
		cwd: CWD,
		agentDir: AGENT_DIR,
		systemPrompt: "You are Heph.",
		turnApi: () => {
			const api = worker.api();
			return {
				...api,
				end: async (end) => {
					if (refused.length === 0) {
						refused.push(structuredClone(end));
						throw tooLarge("end of turn is larger than the worker accepts");
					}
					await api.end(end);
				},
			};
		},
		models: fauxModels([
			() =>
				faux.fauxAssistantMessage(
					[faux.fauxToolCall("fetch_context", { path: "inputs/context/user.json" })],
					{ stopReason: "toolUse" },
				),
			() => faux.fauxAssistantMessage("read it"),
		]),
	});
	host.start(crypto.randomUUID(), 1, "turn-token");
	const end = await worker.waitForEnd();
	await host.idle();

	const resultOf = (entries: unknown[]) =>
		textOf(messages(entries.filter(isSessionEntry), "toolResult")[0]?.message);
	assert.match(resultOf(refused[0]?.entries ?? []), /inputs\/context\/user\.json/u);
	assert.doesNotMatch(resultOf(refused[0]?.entries ?? []), /read at/u);
	assert.equal(end.outcome, "DONE");
	assert.equal(end.entries.length, refused[0]?.entries.length);
	assert.match(resultOf(end.entries), /^\[fetch_context inputs\/context\/user\.json, read at /u);
	assert.equal(textOf(messages(worker.appended, "assistant").at(-1)?.message), "read it");

	// The next turn finds the note already stored and edits nothing.
	const next = fakeWorker(contextOf(worker.ledger(), "again"));
	await runTurn(next, fauxModels([() => faux.fauxAssistantMessage("again")]));
	assert.ok(next.appended.every((entry) => entry.type !== "context_edit"));
});

/** One final, unmasked server frame with a payload shorter than 126 bytes. */
function frame(opcode: number, payload = ""): Buffer {
	const bytes = Buffer.from(payload);
	return Buffer.concat([Buffer.from([0x80 + opcode, bytes.length]), bytes]);
}

void test("the command channel carries the host's credential, runs its commands, answers pings and ends when it closes", async () => {
	const server = createServer();
	const connected = Promise.withResolvers<{
		authorization: string | undefined;
		path: string | undefined;
		client: Socket;
	}>();
	server.on("upgrade", (request: IncomingMessage, client: Socket) => {
		const accept = createHash("sha1")
			.update(`${String(request.headers["sec-websocket-key"])}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
			.digest("base64");
		client.write(
			"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
				`Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
		);
		connected.resolve({ authorization: request.headers.authorization, path: request.url, client });
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address === "object");
	const commands: string[] = [];
	const started = Promise.withResolvers<undefined>();
	const stopped = Promise.withResolvers<undefined>();
	try {
		const channel = runCommands(
			`http://127.0.0.1:${address.port}/internal/llm/runtime/host-1`,
			"host-token",
			{
				start: (turnId, attempt, token) => {
					commands.push(`start ${turnId} ${attempt} ${token}`);
					started.resolve(undefined);
				},
				stop: (turnId, attempt, reason) => {
					commands.push(`stop ${turnId} ${attempt} ${reason}`);
					stopped.resolve(undefined);
				},
			},
		);
		const { authorization, path, client } = await connected.promise;
		assert.equal(authorization, "Bearer host-token");
		assert.equal(path, "/internal/llm/runtime/host-1/commands");

		client.write(
			frame(0x1, JSON.stringify({ type: "start", turnId: "t1", attempt: 1, token: "turn-token" })),
		);
		await started.promise;
		client.write(
			frame(0x1, JSON.stringify({ type: "stop", turnId: "t1", attempt: 1, reason: "developer" })),
		);
		await stopped.promise;
		// The worker declares a host lost that answers no ping; Node's client answers without host code.
		client.write(frame(0x9));
		const received: unknown[] = await once(client, "data");
		const pong = received[0];
		assert.ok(Buffer.isBuffer(pong));
		assert.equal(pong[0], 0x8a, "a final pong frame");
		client.destroy();
		await channel;

		assert.deepEqual(commands, ["start t1 1 turn-token", "stop t1 1 developer"]);
	} finally {
		server.close();
	}
});
