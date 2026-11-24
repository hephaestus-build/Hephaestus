// runtime-contract.ts — the runtime contract: what Hephaestus relies on from the loop that runs a session.
//
// Each case builds a session through a loop (Pi through openRunSession), drives it with a
// scripted faux model and checks one promise: what the model is sent, how tools run, what the session log keeps, the
// order of the hooks and events that the run kinds' extensions use, the settle hook, retry and abort. A Pi upgrade
// passes these cases (runtime-contract.spec.ts).
//
// Outside the contract: compaction and the provider hooks (`before_provider_request`, `before_provider_headers`), since
// a faux model builds no provider payload; and the session APIs that only the
// review runner uses (steering, queues, compaction control), which an exit from Pi would still have to replace.

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import {
	type AssistantMessage,
	type FauxResponseStep,
	fauxAssistantMessage,
	fauxProvider,
	fauxToolCall,
	getSystemMessageText,
	type Message,
	type SimpleStreamOptions,
} from "@earendil-works/pi-ai";
import {
	defineTool,
	type ExtensionFactory,
	ModelRuntime,
	SessionManager,
	type ToolDefinition,
} from "@earendil-works/pi-coding-agent";

import type { RunDescriptor, RunPlace } from "../../../main/resources/agent/pi-session.ts";

/** What a run kind uses of a session. */
export interface RunSession {
	prompt: (text: string) => Promise<void>;
	waitForIdle: () => Promise<void>;
	abort: () => Promise<void>;
	subscribe: (listener: (event: RunEvent) => void) => () => void;
	dispose: () => void;
}

/** An event of a session, as far as the run kinds read it. */
export interface RunEvent {
	readonly type: string;
	readonly message?: { readonly role: string };
	readonly toolName?: string;
	readonly toolCallId?: string;
	readonly args?: unknown;
	readonly result?: unknown;
	readonly isError?: boolean;
	readonly attempt?: number;
	readonly maxAttempts?: number;
	readonly delayMs?: number;
	readonly success?: boolean;
	readonly aborted?: boolean;
}

/** Builds a session from a run descriptor. */
export type RunLoop = (place: RunPlace, descriptor: RunDescriptor) => Promise<RunSession>;

/** What one request to the model carried. */
interface Request {
	system: string;
	tools: { name: string; parameters: unknown }[];
	messages: Message[];
	reasoning: unknown;
}

function requestOf(messages: Message[], options: SimpleStreamOptions | undefined): Request {
	const system = messages.filter((message) => message.role === "system");
	return {
		system: system.map((message) => getSystemMessageText(message)).join("\n"),
		tools: system.flatMap(
			(message) =>
				message.toolsAdded?.map((tool) => ({ name: tool.name, parameters: tool.parameters })) ?? [],
		),
		messages: messages.filter((message) => message.role !== "system"),
		reasoning: options?.reasoning,
	};
}

/** The messages of the session log that a model reads back: the question, the answers and the tool results. */
export function loggedMessages(log: SessionManager): Message[] {
	return log.getEntries().flatMap((entry) => {
		if (entry.type !== "message") {
			return [];
		}
		const { message } = entry;
		return message.role === "user" || message.role === "assistant" || message.role === "toolResult"
			? [message]
			: [];
	});
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function textOf(message: Message | undefined): string {
	if (message === undefined || message.role === "system") {
		return "";
	}
	const { content } = message;
	if (typeof content === "string") {
		return content;
	}
	return content.map((block) => (block.type === "text" ? block.text : "")).join("");
}

/** A run whose model answers with the given steps; it records each request, event and hook. */
interface Run {
	session: RunSession;
	requests: Request[];
	events: RunEvent[];
	hooks: string[];
	executed: unknown[];
	log: SessionManager;
	close: () => void;
}

interface RunOptions {
	steps: FauxResponseStep[];
	extensions?: ExtensionFactory[];
	settings?: RunDescriptor["settings"];
	tokensPerSecond?: number;
	/** The session log to continue, such as the log of an earlier run. */
	log?: SessionManager;
	/** A thinking level for a model that reasons. */
	thinkingLevel?: RunDescriptor["thinkingLevel"];
}

/** The echo tool's schema, as the model must get it; the tool gets its own copy. */
const ECHO_PARAMETERS = Object.freeze({
	type: "object",
	additionalProperties: false,
	required: ["text"],
	properties: { text: { type: "string" } },
});

const SYSTEM_PROMPT = "Answer from the evidence only.";

async function start(loop: RunLoop, options: RunOptions): Promise<Run> {
	const cwd = mkdtempSync(path.join(tmpdir(), "runtime-contract-"));
	const requests: Request[] = [];
	const executed: unknown[] = [];
	const hooks: string[] = [];
	const provider = `faux-${crypto.randomUUID()}`;
	const faux = fauxProvider({
		provider,
		tokensPerSecond: options.tokensPerSecond ?? 100_000,
		...(options.thinkingLevel === undefined
			? {}
			: { models: [{ id: "faux-reasoning", reasoning: true }] }),
	});
	faux.setResponses(
		options.steps.map((step) =>
			typeof step === "function"
				? async (context, streamOptions, state, model) => {
						requests.push(requestOf(context.messages, streamOptions));
						return step(context, streamOptions, state, model);
					}
				: (context, streamOptions) => {
						requests.push(requestOf(context.messages, streamOptions));
						return step;
					},
		),
	);
	const modelRuntime = await ModelRuntime.create({
		authPath: path.join(cwd, "auth.json"),
		modelsPath: null,
		allowModelNetwork: false,
	});
	modelRuntime.registerNativeProvider(faux.provider);
	const model = modelRuntime.getModel(provider, faux.getModel().id);
	assert.ok(model);
	const echo: ToolDefinition = defineTool({
		name: "echo",
		label: "Echo",
		description: "Says the text back.",
		parameters: structuredClone(ECHO_PARAMETERS),
		execute: async (toolCallId, params) => {
			executed.push([toolCallId, params]);
			hooks.push("execute");
			return {
				content: [{ type: "text", text: `echo: ${String(params.text)}` }],
				details: { source: "echo" },
			};
		},
	});
	const recorder: ExtensionFactory = (pi) => {
		pi.on("context", () => {
			hooks.push("context");
		});
		pi.on("tool_call", () => {
			hooks.push("tool_call");
		});
		pi.on("agent_before_settle", () => {
			hooks.push("agent_before_settle");
		});
		pi.on("agent_settled", () => {
			hooks.push("agent_settled");
		});
	};
	const log = options.log ?? SessionManager.inMemory(cwd);
	const session = await loop(
		{ cwd, agentDir: cwd },
		{
			modelRuntime,
			model,
			thinkingLevel: options.thinkingLevel,
			systemPrompt: SYSTEM_PROMPT,
			tools: ["echo"],
			customTools: [echo],
			extensions: [recorder, ...(options.extensions ?? [])],
			settings: options.settings ?? { compaction: { enabled: false }, retry: { enabled: false } },
			sessionManager: log,
		},
	);
	const events: RunEvent[] = [];
	const unsubscribe = session.subscribe((event) => {
		events.push(event);
	});
	return {
		session,
		requests,
		events,
		hooks,
		executed,
		log,
		close: () => {
			unsubscribe();
			session.dispose();
			rmSync(cwd, { recursive: true, force: true });
		},
	};
}

async function ask(run: Run, question: string): Promise<void> {
	await run.session.prompt(question);
	await run.session.waitForIdle();
}

const providerFailure = (): AssistantMessage =>
	fauxAssistantMessage([], { stopReason: "error", errorMessage: "503 overloaded" });

/** The scripted call ID: an observed ID must equal it, since two missing or reused IDs compare equal. */
const callIdFor = (text: string): string => `echo-${text}`;

const toolCall = (text: string): AssistantMessage =>
	fauxAssistantMessage(fauxToolCall("echo", { text }, { id: callIdFor(text) }), {
		stopReason: "toolUse",
	});

/** Two calls in turn, so that a result paired with the wrong call shows. */
const TWO_CALLS = ["hi", "bye"];

function exchange(messages: readonly Message[]): string[][] {
	return messages.flatMap((message) => {
		if (message.role === "assistant") {
			return message.content.flatMap((block) =>
				block.type === "toolCall"
					? [["call", block.id, block.name, JSON.stringify(block.arguments)]]
					: [],
			);
		}
		return message.role === "toolResult"
			? [["result", message.toolCallId, message.toolName, textOf(message)]]
			: [];
	});
}

const echoExchange = (texts: readonly string[]): string[][] =>
	texts.flatMap((text) => [
		["call", callIdFor(text), "echo", JSON.stringify({ text })],
		["result", callIdFor(text), "echo", `echo: ${text}`],
	]);

/** The events that the run kinds read, by type and role. */
function trace(events: readonly RunEvent[]): string[] {
	const read = new Set([
		"turn_start",
		"turn_end",
		"tool_execution_start",
		"tool_execution_end",
		"agent_end",
		"agent_settled",
	]);
	return events.flatMap((event) => {
		if (event.type === "message_end") {
			const role = event.message?.role;
			return role === "assistant" || role === "toolResult" ? [`message_end:${role}`] : [];
		}
		return read.has(event.type) ? [event.type] : [];
	});
}

function settled(events: readonly RunEvent[]): RunEvent[] {
	return events.filter((event) => event.type === "agent_settled");
}

/** One promise of the contract. */
export interface ContractCase {
	name: string;
	run: (loop: RunLoop) => Promise<void>;
}

export const SYSTEM_AND_TOOLS: ContractCase = {
	name: "the model is sent the descriptor's system prompt and tools, and the question",
	run: async (loop) => {
		const run = await start(loop, { steps: [fauxAssistantMessage("answer")] });
		try {
			await ask(run, "question");
			const [request] = run.requests;
			assert.ok(request);
			assert.match(request.system, /Answer from the evidence only\./u);
			assert.deepEqual(request.tools, [{ name: "echo", parameters: ECHO_PARAMETERS }]);
			assert.deepEqual(
				request.messages.map((message) => [message.role, textOf(message)]),
				[["user", "question"]],
			);
		} finally {
			run.close();
		}
	},
};

export const TOOL_ROUND_TRIP: ContractCase = {
	name: "a tool call runs the tool, its result reaches the next request, and the run settles once",
	run: async (loop) => {
		const run = await start(loop, {
			steps: [...TWO_CALLS.map(toolCall), fauxAssistantMessage("done")],
		});
		try {
			await ask(run, "question");
			// A tool, the model and the review's read observer each tell calls apart by the call ID.
			assert.deepEqual(
				run.executed,
				TWO_CALLS.map((text) => [callIdFor(text), { text }]),
			);
			assert.deepEqual(
				run.requests.slice(1).map((request) => exchange(request.messages)),
				TWO_CALLS.map((_, sent) => echoExchange(TWO_CALLS.slice(0, sent + 1))),
			);
			assert.deepEqual(
				run.events
					.filter((event) => event.type === "tool_execution_start")
					.map((event) => [event.toolName, event.toolCallId, event.args]),
				TWO_CALLS.map((text) => ["echo", callIdFor(text), { text }]),
			);
			// The read observer qualifies evidence by the result's content and details.
			assert.deepEqual(
				run.events
					.filter((event) => event.type === "tool_execution_end")
					.map(({ toolName, toolCallId, isError, result }) => [
						toolName,
						toolCallId,
						isError,
						isRecord(result) ? [result.content, result.details] : result,
					]),
				TWO_CALLS.map((text) => [
					"echo",
					callIdFor(text),
					false,
					[[{ type: "text", text: `echo: ${text}` }], { source: "echo" }],
				]),
			);
			assert.deepEqual(
				settled(run.events).map((event) => event.aborted),
				[false],
			);
		} finally {
			run.close();
		}
	},
};

export const SESSION_LOG: ContractCase = {
	name: "the session log keeps the run's messages in order",
	run: async (loop) => {
		const run = await start(loop, {
			steps: [...TWO_CALLS.map(toolCall), fauxAssistantMessage("done")],
		});
		try {
			await ask(run, "question");
			const messages = loggedMessages(run.log);
			assert.deepEqual(
				messages.map((message) => [message.role, textOf(message)]),
				[
					["user", "question"],
					...TWO_CALLS.flatMap((text) => [
						["assistant", ""],
						["toolResult", `echo: ${text}`],
					]),
					["assistant", "done"],
				],
			);
			assert.deepEqual(exchange(messages), echoExchange(TWO_CALLS));
		} finally {
			run.close();
		}
	},
};

export const HOOK_ORDER: ContractCase = {
	name: "hooks and events come in the order that the run kinds rely on",
	run: async (loop) => {
		const run = await start(loop, { steps: [toolCall("hi"), fauxAssistantMessage("done")] });
		try {
			await ask(run, "question");
			assert.deepEqual(run.hooks, [
				"context",
				"tool_call",
				"execute",
				"context",
				"agent_before_settle",
				"agent_settled",
			]);
			assert.deepEqual(trace(run.events), [
				"turn_start",
				"message_end:assistant",
				"tool_execution_start",
				"tool_execution_end",
				"message_end:toolResult",
				"turn_end",
				"turn_start",
				"message_end:assistant",
				"turn_end",
				"agent_end",
				"agent_settled",
			]);
		} finally {
			run.close();
		}
	},
};

export const BLOCKED_TOOL: ContractCase = {
	name: "a tool_call hook that blocks keeps the tool from running and tells the model why",
	run: async (loop) => {
		const run = await start(loop, {
			steps: [toolCall("hi"), fauxAssistantMessage("done")],
			extensions: [
				(pi) => {
					// As the declared-sources hook does, the decision reads the call's tool and input.
					pi.on("tool_call", (event) =>
						event.toolName === "echo" && event.input.text === "hi"
							? { block: true, reason: "outside the declared sources" }
							: undefined,
					);
				},
			],
		});
		try {
			await ask(run, "question");
			assert.deepEqual(run.executed, []);
			const last = run.requests[1]?.messages.at(-1);
			assert.ok(last?.role === "toolResult" && last.isError);
			assert.match(textOf(last), /outside the declared sources/u);
			// A conversation tells a refused call apart by its ID, in the model's request and in the log.
			const blocked = [
				["call", callIdFor("hi"), "echo"],
				["result", callIdFor("hi"), "echo"],
			];
			for (const messages of [run.requests[1]?.messages ?? [], loggedMessages(run.log)]) {
				assert.deepEqual(
					exchange(messages).map((entry) => entry.slice(0, 3)),
					blocked,
				);
			}
			// The review's read observer pairs a failed read with its call by the end event's ID.
			assert.deepEqual(
				run.events
					.filter((event) => event.type.startsWith("tool_execution_"))
					.map((event) => [event.type, event.toolName, event.toolCallId, event.isError === true]),
				[
					["tool_execution_start", "echo", callIdFor("hi"), false],
					["tool_execution_end", "echo", callIdFor("hi"), true],
				],
			);
		} finally {
			run.close();
		}
	},
};

export const CONTEXT_HOOK: ContractCase = {
	name: "context hooks decide in turn what the model sees, and the log keeps only the run's own messages",
	run: async (loop) => {
		const seen: string[] = [];
		const run = await start(loop, {
			steps: [fauxAssistantMessage("answer")],
			extensions: [
				(pi) => {
					pi.on("context", (event) => ({
						messages: [
							...event.messages,
							{ role: "user", content: "situation", timestamp: Date.now() },
						],
					}));
				},
				(pi) => {
					pi.on("context", (event) => {
						const last = event.messages.at(-1);
						seen.push(
							last?.role === "user" && typeof last.content === "string" ? last.content : "",
						);
					});
				},
			],
		});
		try {
			await ask(run, "question");
			assert.deepEqual(seen, ["situation"]);
			assert.equal(textOf(run.requests[0]?.messages.at(-1)), "situation");
			const logged = loggedMessages(run.log).map((message) => textOf(message));
			assert.ok(!logged.includes("situation"));
		} finally {
			run.close();
		}
	},
};

export const RETRY: ContractCase = {
	name: "a transient provider failure is retried by the loop, and the run settles with the answer",
	run: async (loop) => {
		const run = await start(loop, {
			steps: [providerFailure(), fauxAssistantMessage("answer")],
			settings: {
				compaction: { enabled: false },
				retry: { enabled: true, maxRetries: 2, baseDelayMs: 1 },
			},
		});
		try {
			await ask(run, "question");
			assert.equal(run.requests.length, 2);
			assert.deepEqual(
				run.events
					.filter((event) => event.type.startsWith("auto_retry_"))
					.map((event) => [event.type, event.attempt, event.success]),
				[
					["auto_retry_start", 1, undefined],
					["auto_retry_end", 1, true],
				],
			);
			const answers = loggedMessages(run.log).filter((message) => message.role === "assistant");
			assert.equal(textOf(answers.at(-1)), "answer");
			assert.deepEqual(
				settled(run.events).map((event) => event.aborted),
				[false],
			);
		} finally {
			run.close();
		}
	},
};

export const ABORT: ContractCase = {
	name: "an abort ends the model call or a retry's wait, and the run settles as aborted",
	run: async (loop) => {
		await abortDuringAnswer(loop);
		await abortDuringRetry(loop);
	},
};

async function abortDuringAnswer(loop: RunLoop): Promise<void> {
	const run = await start(loop, {
		steps: [fauxAssistantMessage("word ".repeat(100))],
		tokensPerSecond: 50,
	});
	try {
		const streaming = Promise.withResolvers<undefined>();
		const stop = run.session.subscribe((event) => {
			if (event.type === "message_update") {
				stop();
				streaming.resolve(undefined);
			}
		});
		const prompted = run.session.prompt("question");
		await streaming.promise;
		await run.session.abort();
		await prompted;
		await run.session.waitForIdle();
		assert.deepEqual(
			settled(run.events).map((event) => event.aborted),
			[true],
		);
		const answer = loggedMessages(run.log).at(-1);
		assert.ok(answer?.role === "assistant" && answer.stopReason === "aborted");
	} finally {
		run.close();
	}
}

/** An abort while the loop waits to retry a provider failure ends the retry as unsuccessful. */
async function abortDuringRetry(loop: RunLoop): Promise<void> {
	const run = await start(loop, {
		steps: [providerFailure(), fauxAssistantMessage("answer")],
		settings: {
			compaction: { enabled: false },
			retry: { enabled: true, maxRetries: 2, baseDelayMs: 60_000 },
		},
	});
	try {
		const waiting = Promise.withResolvers<undefined>();
		const stop = run.session.subscribe((event) => {
			if (event.type === "auto_retry_start") {
				stop();
				waiting.resolve(undefined);
			}
		});
		const prompted = run.session.prompt("question");
		await waiting.promise;
		await run.session.abort();
		await prompted;
		await run.session.waitForIdle();
		assert.equal(run.requests.length, 1);
		assert.deepEqual(
			run.events.filter((event) => event.type === "auto_retry_end").map((event) => event.success),
			[false],
		);
		assert.deepEqual(
			settled(run.events).map((event) => event.aborted),
			[true],
		);
	} finally {
		run.close();
	}
}

export const SETTLE_ENTRIES: ContractCase = {
	name: "a settle hook learns that the run completed, and a later run sees the context edits it returned",
	run: async (loop) => {
		const outcomes: unknown[] = [];
		let log: SessionManager | null = null;
		const run = await start(loop, {
			steps: [toolCall("hi"), fauxAssistantMessage("done")],
			extensions: [
				(pi) => {
					pi.on("agent_before_settle", (event) => {
						outcomes.push(event.outcome);
						const result = log
							?.getEntries()
							.find((entry) => entry.type === "message" && entry.message.role === "toolResult");
						return result === undefined
							? undefined
							: {
									entries: [
										{
											type: "context_edit",
											targetId: result.id,
											replacement: { content: [{ type: "text", text: "a note" }] },
										},
									],
								};
					});
				},
			],
		});
		log = run.log;
		try {
			await ask(run, "question");
			assert.deepEqual(outcomes, ["completed"]);
		} finally {
			run.close();
		}
		// A conversation's next turn rebuilds its log from the stored entries: the model sees the edit, not the result
		// it replaced.
		const stored = SessionManager.inMemory(undefined, undefined, run.log.getEntries());
		const next = await start(loop, { steps: [fauxAssistantMessage("again")], log: stored });
		try {
			await ask(next, "next");
			const seen = next.requests[0]?.messages ?? [];
			const result = seen.find((message) => message.role === "toolResult");
			assert.equal(textOf(result), "a note");
			assert.equal(textOf(seen.at(-1)), "next");
		} finally {
			next.close();
		}
	},
};

export const REASONING: ContractCase = {
	name: "the model is asked to reason at the descriptor's thinking level",
	run: async (loop) => {
		const run = await start(loop, {
			steps: [fauxAssistantMessage("answer")],
			thinkingLevel: "low",
		});
		try {
			await ask(run, "question");
			assert.equal(run.requests[0]?.reasoning, "low");
		} finally {
			run.close();
		}
	},
};

export const RETRY_EXHAUSTED: ContractCase = {
	name: "a provider failure that the retries do not cure ends the run with the failure, once",
	run: async (loop) => {
		const unauthorized = fauxAssistantMessage([], {
			stopReason: "error",
			errorMessage: "401 unauthorized",
		});
		// Retries that run out, and a retry that meets a failure no retry cures while another retry is left.
		for (const [steps, maxRetries] of [
			[[providerFailure(), providerFailure()], 1],
			[[providerFailure(), unauthorized], 2],
		] as const) {
			const outcomes: unknown[] = [];
			const run = await start(loop, {
				steps: [...steps],
				settings: {
					compaction: { enabled: false },
					retry: { enabled: true, maxRetries, baseDelayMs: 1 },
				},
				extensions: [
					(pi) => {
						pi.on("agent_before_settle", (event) => {
							outcomes.push(event.outcome);
						});
					},
				],
			});
			try {
				await ask(run, "question");
				assert.equal(run.requests.length, 2);
				// The review runner accounts an unsuccessful retry from these events.
				const retries = run.events.filter((event) => event.type.startsWith("auto_retry_"));
				assert.deepEqual(
					retries.map((event) => [event.type, event.attempt, event.maxAttempts, event.success]),
					[
						["auto_retry_start", 1, maxRetries, undefined],
						["auto_retry_end", 1, undefined, false],
					],
				);
				assert.equal(typeof retries[0]?.delayMs, "number");
				const answer = loggedMessages(run.log).at(-1);
				assert.ok(answer?.role === "assistant" && answer.stopReason === "error");
				// A conversation writes its context edits only for a completed run.
				assert.deepEqual(outcomes, ["error"]);
				assert.deepEqual(
					settled(run.events).map((event) => event.aborted),
					[false],
				);
			} finally {
				run.close();
			}
		}
	},
};

export const CONTRACT: readonly ContractCase[] = [
	SYSTEM_AND_TOOLS,
	TOOL_ROUND_TRIP,
	SESSION_LOG,
	HOOK_ORDER,
	BLOCKED_TOOL,
	CONTEXT_HOOK,
	RETRY,
	ABORT,
	SETTLE_ENTRIES,
	REASONING,
	RETRY_EXHAUSTED,
];
