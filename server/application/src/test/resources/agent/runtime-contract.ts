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

export const CONTRACT: readonly ContractCase[] = [
	SYSTEM_AND_TOOLS,
	TOOL_ROUND_TRIP,
	SESSION_LOG,
	HOOK_ORDER,
	BLOCKED_TOOL,
];
