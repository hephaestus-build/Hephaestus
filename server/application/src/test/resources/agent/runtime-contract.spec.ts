import assert from "node:assert/strict";
import test from "node:test";

import {
	type AssistantMessage,
	type AssistantMessageEventStream,
	createAssistantMessageEventStream,
} from "@earendil-works/pi-ai";
import { SessionManager, type ExtensionFactory } from "@earendil-works/pi-coding-agent";

import { openRunSession, type RunDescriptor } from "../../../main/resources/agent/pi-session.ts";
import {
	ABORT,
	BLOCKED_TOOL,
	CONTEXT_HOOK,
	CONTRACT,
	type ContractCase,
	HOOK_ORDER,
	REASONING,
	RETRY,
	RETRY_EXHAUSTED,
	type RunEvent,
	type RunLoop,
	type RunSession,
	SESSION_LOG,
	SETTLE_ENTRIES,
	SYSTEM_AND_TOOLS,
	TOOL_ROUND_TRIP,
} from "./runtime-contract.ts";
import { secondLoop } from "./runtime-second-loop.ts";

const pi: RunLoop = async (place, descriptor) => {
	const { session } = await openRunSession(place, descriptor);
	return session;
};

/** A contract case that hangs, such as on an event that a Pi build no longer sends, fails instead. */
const CASE_TIME = { timeout: 30_000 };

for (const [loopName, loop] of [
	["Pi", pi],
	["the second loop", secondLoop],
] as const) {
	for (const contractCase of CONTRACT) {
		void test(`${loopName}: ${contractCase.name}`, CASE_TIME, async () => {
			await contractCase.run(loop);
		});
	}
}

// ------------------------------------------------------------ Pi builds that break one promise each

/** Pi, with each hook that an extension registers rewritten: a hook can move to another event or lose its result. */
function rehooked(rewrite: (name: string, handler: unknown) => [string, unknown]): RunLoop {
	const extension =
		(original: ExtensionFactory): ExtensionFactory =>
		async (api) => {
			const on = (name: string, handler: unknown): unknown =>
				Reflect.apply(Reflect.get(api, "on"), api, rewrite(name, handler));
			await original(
				new Proxy(api, {
					get: (target, property): unknown =>
						property === "on" ? on : (Reflect.get(target, property) as unknown),
				}),
			);
		};
	return async (place, descriptor) =>
		pi(place, { ...descriptor, extensions: descriptor.extensions.map(extension) });
}

/** A hook that still runs but whose result Pi ignores. */
function ignoring(hook: string): RunLoop {
	return rehooked((name, handler) =>
		name === hook && typeof handler === "function"
			? [
					name,
					(event: unknown, context: unknown) => {
						Reflect.apply(handler, undefined, [event, context]);
					},
				]
			: [name, handler],
	);
}

function changed(change: (descriptor: RunDescriptor) => RunDescriptor): RunLoop {
	return async (place, descriptor) => pi(place, change(descriptor));
}

function rewired(
	change: (session: RunSession) => Partial<RunSession>,
	loop: RunLoop = pi,
): RunLoop {
	return async (place, descriptor) => {
		const session = await loop(place, descriptor);
		return {
			prompt: async (text) => session.prompt(text),
			waitForIdle: async () => session.waitForIdle(),
			abort: async () => session.abort(),
			subscribe: (listener) => session.subscribe(listener),
			dispose: () => {
				session.dispose();
			},
			...change(session),
		};
	};
}

function withCallId(id: string): RunLoop {
	const replace: ExtensionFactory = (api) => {
		api.on("context", (event) => ({
			messages: event.messages.map((message) => {
				if (message.role === "toolResult") {
					return { ...message, toolCallId: id };
				}
				if (message.role === "assistant") {
					const content = message.content.map((block) =>
						block.type === "toolCall" ? { ...block, id } : block,
					);
					return { ...message, content };
				}
				return message;
			}),
		}));
	};
	return rewired(
		(session) => ({
			subscribe: relayed(session, (event, emit) => {
				emit(event.type.startsWith("tool_execution_") ? { ...event, toolCallId: id } : event);
			}),
		}),
		changed((descriptor) => ({ ...descriptor, extensions: [...descriptor.extensions, replace] })),
	);
}

function relayed(
	session: RunSession,
	rewrite: (event: RunEvent, emit: (event: RunEvent) => void) => void,
): RunSession["subscribe"] {
	return (listener) =>
		session.subscribe((event) => {
			rewrite(event, listener);
		});
}

const NO_RETRY = { compaction: { enabled: false }, retry: { enabled: false } };

/** An unauthorized request read as a transient failure, which Pi then retries. */
function asTransient(message: AssistantMessage): AssistantMessage {
	return message.errorMessage?.includes("401") === true
		? { ...message, errorMessage: "503 overloaded" }
		: message;
}

function unauthorizedAsTransient(stream: AssistantMessageEventStream): AssistantMessageEventStream {
	const answers = createAssistantMessageEventStream();
	void (async () => {
		for await (const event of stream) {
			answers.push(event.type === "error" ? { ...event, error: asTransient(event.error) } : event);
		}
		answers.end(asTransient(await stream.result()));
	})();
	return answers;
}

const BROKEN: [ContractCase, string, RunLoop][] = [
	[
		SYSTEM_AND_TOOLS,
		"the system prompt is replaced",
		changed((descriptor) => ({ ...descriptor, systemPrompt: "Answer freely." })),
	],
	[
		SYSTEM_AND_TOOLS,
		"a tool's schema loses its required fields",
		changed((descriptor) => ({
			...descriptor,
			customTools: descriptor.customTools.map((tool) => ({
				...tool,
				parameters: { ...tool.parameters, required: [] },
			})),
		})),
	],
	[
		TOOL_ROUND_TRIP,
		"tool events lose their call ID",
		rewired((session) => ({
			subscribe: relayed(session, (event, emit) => {
				emit(
					event.type.startsWith("tool_execution_") ? { ...event, toolCallId: undefined } : event,
				);
			}),
		})),
	],
	[
		TOOL_ROUND_TRIP,
		"the model's call, its result and the tool events all lose their call ID",
		withCallId(""),
	],
	[
		TOOL_ROUND_TRIP,
		"every call, result and tool event carries the first call's ID",
		withCallId("echo-hi"),
	],
	[
		TOOL_ROUND_TRIP,
		"the tool end loses its result",
		rewired((session) => ({
			subscribe: relayed(session, (event, emit) => {
				emit(
					event.type === "tool_execution_end"
						? { ...event, result: { unrelated: "echo: hi" } }
						: event,
				);
			}),
		})),
	],
	[
		SESSION_LOG,
		"the session writes to a log of its own",
		changed((descriptor) => ({ ...descriptor, sessionManager: SessionManager.inMemory() })),
	],
	[
		HOOK_ORDER,
		"the `tool_call` hook fires after the tool ran",
		rehooked((name, handler) => [name === "tool_call" ? "tool_result" : name, handler]),
	],
	[
		HOOK_ORDER,
		"a tool's start arrives after its end",
		rewired((session) => {
			let held: RunEvent | null = null;
			return {
				subscribe: relayed(session, (event, emit) => {
					if (event.type === "tool_execution_start") {
						held = event;
						return;
					}
					emit(event);
					if (event.type === "tool_execution_end" && held !== null) {
						emit(held);
						held = null;
					}
				}),
			};
		}),
	],
	[BLOCKED_TOOL, "a block is ignored", ignoring("tool_call")],
	[
		BLOCKED_TOOL,
		"the `tool_call` hook gets no tool name or input",
		rehooked((name, handler) =>
			name === "tool_call" && typeof handler === "function"
				? [
						name,
						(event: unknown, context: unknown): unknown =>
							Reflect.apply(handler, undefined, [
								{ ...(typeof event === "object" ? event : {}), toolName: "", input: {} },
								context,
							]),
					]
				: [name, handler],
		),
	],
	[CONTEXT_HOOK, "the context hooks' messages are ignored", ignoring("context")],
	[
		RETRY,
		"the loop does not retry",
		changed((descriptor) => ({ ...descriptor, settings: NO_RETRY })),
	],
	[
		RETRY,
		"the retry's end is not reported",
		rewired((session) => ({
			subscribe: relayed(session, (event, emit) => {
				if (event.type !== "auto_retry_end") {
					emit(event);
				}
			}),
		})),
	],
	[
		RETRY_EXHAUSTED,
		"the settle hook hears that a failed run completed",
		rehooked((name, handler) =>
			name === "agent_before_settle" && typeof handler === "function"
				? [
						name,
						(event: unknown, context: unknown): unknown =>
							Reflect.apply(handler, undefined, [
								{ ...(typeof event === "object" ? event : {}), outcome: "completed" },
								context,
							]),
					]
				: [name, handler],
		),
	],
	[
		RETRY_EXHAUSTED,
		"a failure that no retry cures is retried",
		changed((descriptor) => ({
			...descriptor,
			modelRuntime: new Proxy(descriptor.modelRuntime, {
				get: (target, property): unknown =>
					property === "streamSimple"
						? (...call: Parameters<typeof target.streamSimple>) =>
								unauthorizedAsTransient(target.streamSimple(...call))
						: (Reflect.get(target, property) as unknown),
			}),
		})),
	],
	[
		RETRY_EXHAUSTED,
		"an unsuccessful retry is reported as a success",
		rewired((session) => ({
			subscribe: relayed(session, (event, emit) => {
				emit(event.type === "auto_retry_end" ? { ...event, success: true } : event);
			}),
		})),
	],
	[ABORT, "an abort does nothing", rewired(() => ({ abort: async () => undefined }))],
	[
		ABORT,
		"a retry cancelled during its wait is reported as a success",
		rewired((session) => ({
			subscribe: relayed(session, (event, emit) => {
				emit(event.type === "auto_retry_end" ? { ...event, success: true } : event);
			}),
		})),
	],
	[SETTLE_ENTRIES, "the settle hook's entries are ignored", ignoring("agent_before_settle")],
	[
		SETTLE_ENTRIES,
		"a reopened log loses its context edits",
		changed((descriptor) => {
			const entries = descriptor.sessionManager.getEntries();
			return entries.some((entry) => entry.type === "context_edit")
				? {
						...descriptor,
						sessionManager: SessionManager.inMemory(
							undefined,
							undefined,
							entries.filter((entry) => entry.type !== "context_edit"),
						),
					}
				: descriptor;
		}),
	],
	[
		REASONING,
		"the thinking level is dropped",
		changed((descriptor) => ({ ...descriptor, thinkingLevel: undefined })),
	],
];

for (const [contractCase, breach, loop] of BROKEN) {
	void test(`the contract fails on a Pi build where ${breach}`, CASE_TIME, async () => {
		await assert.rejects(contractCase.run(loop), assert.AssertionError);
	});
}

void test("every promise of the contract fails on some broken Pi build", () => {
	const broken = new Set(BROKEN.map(([contractCase]) => contractCase));
	assert.deepEqual(
		CONTRACT.filter((contractCase) => !broken.has(contractCase)).map(
			(contractCase) => contractCase.name,
		),
		[],
	);
});
