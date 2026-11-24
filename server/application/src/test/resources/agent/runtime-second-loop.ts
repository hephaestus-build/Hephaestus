// runtime-second-loop.ts — a minimal second agent loop that meets the runtime contract (runtime-contract.ts).
//
// It keeps Pi's provider layer (pi-ai) and session log, and replaces Pi's agent session with a few hundred lines that
// meet the contract: the hooks that the run kinds' extensions use, the session's own tools, the settle hook's context
// edits, the loop's retry and abort. It has no built-in tools, no compaction, no tool context and no steering, which an
// exit from Pi would add. Only tests use it.

import { setTimeout as sleep } from "node:timers/promises";

import {
	type AssistantMessage,
	isRetryableAssistantError,
	type JsonValue,
	type Message,
	type TextContent,
	type ToolCall,
	type ToolResultMessage,
} from "@earendil-works/pi-ai";
import type {
	ExtensionAPI,
	ExtensionToolContext,
	ToolDefinition,
} from "@earendil-works/pi-coding-agent";

import type { RunDescriptor, RunPlace } from "../../../main/resources/agent/pi-session.ts";
import type { RunEvent, RunLoop, RunSession } from "./runtime-contract.ts";

/** The hooks this loop calls. An extension that registers any other hook fails to load. */
const HOOKS = new Set(["context", "tool_call", "agent_before_settle", "agent_settled"]);

type Hook = (event: unknown) => unknown;

/**
 * The extension API this loop offers: `on` for its hooks, nothing else. The cast is the point of the second loop: an
 * extension that reaches for more of Pi's API fails here, which is what an exit from Pi would have to replace.
 */
function extensionApi(hooks: Map<string, Hook[]>): ExtensionAPI {
	const on = (name: string, handler: Hook) => {
		if (!HOOKS.has(name)) {
			throw new Error(`the second loop has no ${name} hook`);
		}
		const list = hooks.get(name) ?? [];
		list.push(handler);
		hooks.set(name, list);
		return () => {
			list.splice(list.indexOf(handler), 1);
		};
	};
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- the second loop offers only `on`, on purpose
	return new Proxy(
		{ on },
		{
			get: (target, property) => {
				if (property === "on") {
					return target.on;
				}
				throw new Error(`the second loop's extension API has no ${String(property)}`);
			},
		},
	) as unknown as ExtensionAPI;
}

/** Tools get no context here; a tool that reads it fails, as it would on a loop that never offered one. */
// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- a tool that reads the context fails, on purpose
const NO_TOOL_CONTEXT = new Proxy(
	{},
	{
		get: (_target, property) => {
			throw new Error(`the second loop gives tools no context (${String(property)})`);
		},
	},
) as ExtensionToolContext;

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Whether a tool's details can be stored in the session log. */
function isJson(value: unknown): value is JsonValue {
	if (value === null || ["boolean", "number", "string"].includes(typeof value)) {
		return true;
	}
	if (Array.isArray(value)) {
		return value.every((item: unknown) => isJson(item));
	}
	return isRecord(value) && Object.values(value).every((item) => isJson(item));
}

/** The messages that a model reads, of a session's resolved context. */
function modelMessages(messages: readonly unknown[]): Message[] {
	return messages.filter(
		(message): message is Message =>
			isRecord(message) &&
			(message.role === "user" || message.role === "assistant" || message.role === "toolResult"),
	);
}

/** What a context hook returns as the messages the model sees. */
function isMessages(value: unknown): value is Message[] {
	return (
		Array.isArray(value) &&
		value.every((message: unknown) => isRecord(message) && typeof message.role === "string")
	);
}

function errorResult(call: ToolCall, text: string): ToolResultMessage {
	return {
		role: "toolResult",
		toolCallId: call.id,
		toolName: call.name,
		content: [{ type: "text", text }],
		isError: true,
		timestamp: Date.now(),
	};
}

class SecondLoopSession implements RunSession {
	readonly #descriptor: RunDescriptor;
	readonly #tools: Map<string, ToolDefinition>;
	readonly #hooks = new Map<string, Hook[]>();
	readonly #listeners = new Set<(event: RunEvent) => void>();
	readonly #messages: Message[] = [];
	#controller = new AbortController();
	#running: Promise<void> = Promise.resolve();

	constructor(descriptor: RunDescriptor) {
		this.#descriptor = descriptor;
		this.#tools = new Map(descriptor.customTools.map((tool) => [tool.name, tool]));
		for (const name of descriptor.tools) {
			if (!this.#tools.has(name)) {
				throw new Error(`the second loop has no built-in tool ${name}`);
			}
		}
		// The log as the model reads it: a context edit replaces the entry it targets.
		this.#messages.push(...modelMessages(descriptor.sessionManager.buildSessionContext().messages));
	}

	async load(): Promise<void> {
		const api = extensionApi(this.#hooks);
		for (const extension of this.#descriptor.extensions) {
			await extension(api);
		}
	}

	subscribe(listener: (event: RunEvent) => void): () => void {
		this.#listeners.add(listener);
		return () => {
			this.#listeners.delete(listener);
		};
	}

	async prompt(text: string): Promise<void> {
		this.#controller = new AbortController();
		this.#running = this.#run(text);
		await this.#running;
	}

	async waitForIdle(): Promise<void> {
		await this.#running;
	}

	async abort(): Promise<void> {
		this.#controller.abort();
		await this.#running;
	}

	dispose(): void {
		this.#listeners.clear();
	}

	#emit(event: RunEvent): void {
		for (const listener of this.#listeners) {
			listener(event);
		}
	}

	async #call(name: string, event: Record<string, unknown>): Promise<unknown[]> {
		const results: unknown[] = [];
		for (const hook of this.#hooks.get(name) ?? []) {
			results.push(await hook(event));
		}
		return results;
	}

	#keep(message: Message): void {
		this.#messages.push(message);
		this.#descriptor.sessionManager.appendMessage(message);
		this.#emit({ type: "message_end", message });
	}

	async #run(text: string): Promise<void> {
		const { signal } = this.#controller;
		this.#emit({ type: "agent_start" });
		this.#keep({ role: "user", content: [{ type: "text", text }], timestamp: Date.now() });
		let answer: AssistantMessage;
		for (;;) {
			this.#emit({ type: "turn_start" });
			answer = await this.#answer(signal);
			this.#keep(answer);
			const calls = answer.content.filter((block): block is ToolCall => block.type === "toolCall");
			if (answer.stopReason === "toolUse" && !signal.aborted) {
				for (const call of calls) {
					this.#keep(await this.#runTool(call, signal));
				}
			}
			this.#emit({ type: "turn_end", message: answer });
			if (answer.stopReason !== "toolUse" || signal.aborted) {
				break;
			}
		}
		this.#emit({ type: "agent_end" });
		let outcome = "completed";
		if (signal.aborted) {
			outcome = "aborted";
		} else if (answer.stopReason === "error") {
			outcome = "error";
		}
		for (const result of await this.#call("agent_before_settle", {
			type: "agent_before_settle",
			outcome,
		})) {
			this.#commit(result);
		}
		const settled = { type: "agent_settled", aborted: signal.aborted };
		await this.#call("agent_settled", settled);
		this.#emit(settled);
	}

	/** Writes the entries that a settle hook returned; this loop writes only context edits. */
	#commit(result: unknown): void {
		if (!isRecord(result) || !Array.isArray(result.entries)) {
			return;
		}
		for (const draft of result.entries) {
			if (!isRecord(draft) || draft.type !== "context_edit" || typeof draft.targetId !== "string") {
				throw new Error("the second loop writes only context edits at settle");
			}
			const { replacement } = draft;
			if (!isRecord(replacement) || !Array.isArray(replacement.content)) {
				throw new Error("a context edit needs replacement content");
			}
			this.#descriptor.sessionManager.appendContextEdit(draft.targetId, {
				content: replacement.content.filter(
					(block: unknown): block is TextContent => isRecord(block) && block.type === "text",
				),
			});
		}
	}

	/** One model answer, after the loop's retries of a transient provider failure. */
	async #answer(signal: AbortSignal): Promise<AssistantMessage> {
		const { retry } = this.#descriptor.settings;
		const retries = retry?.enabled === true ? (retry.maxRetries ?? 3) : 0;
		const { thinkingLevel } = this.#descriptor;
		const reasoning =
			thinkingLevel === undefined || thinkingLevel === "off" ? {} : { reasoning: thinkingLevel };
		for (let attempt = 0; ; attempt += 1) {
			// As in Pi, each context hook gets a copy of what the hook before it decided.
			let messages = [...this.#messages];
			for (const hook of this.#hooks.get("context") ?? []) {
				const result = await hook({ type: "context", messages: structuredClone(messages) });
				if (isRecord(result) && isMessages(result.messages)) {
					messages = result.messages;
				}
			}
			const stream = this.#descriptor.modelRuntime.streamSimple(
				this.#descriptor.model,
				{
					systemPrompt: this.#descriptor.systemPrompt,
					messages,
					tools: [...this.#tools.values()].map((tool) => ({
						name: tool.name,
						description: tool.description,
						parameters: tool.parameters,
					})),
				},
				{ signal, ...reasoning },
			);
			for await (const event of stream) {
				if ("partial" in event) {
					this.#emit({ type: "message_update", message: event.partial });
				}
			}
			const answer = await stream.result();
			const retryable = !signal.aborted && isRetryableAssistantError(answer);
			if (attempt >= retries || !retryable) {
				if (attempt > 0) {
					this.#emit({ type: "auto_retry_end", attempt, success: answer.stopReason !== "error" });
				}
				return answer;
			}
			const delayMs = (retry?.baseDelayMs ?? 1000) * 2 ** attempt;
			this.#emit({
				type: "auto_retry_start",
				attempt: attempt + 1,
				maxAttempts: retries,
				delayMs,
			});
			const waited = await sleep(delayMs, undefined, { signal }).then(
				() => true,
				() => false,
			);
			if (!waited) {
				// As in Pi, an abort during the wait ends the retry as unsuccessful.
				this.#emit({ type: "auto_retry_end", attempt: attempt + 1, success: false });
				return { ...answer, stopReason: "aborted" };
			}
		}
	}

	async #runTool(call: ToolCall, signal: AbortSignal): Promise<ToolResultMessage> {
		this.#emit({
			type: "tool_execution_start",
			toolName: call.name,
			toolCallId: call.id,
			args: call.arguments,
		});
		const result = await this.#result(call, signal);
		this.#emit({
			type: "tool_execution_end",
			toolName: call.name,
			toolCallId: call.id,
			// As Pi does, the end carries what the tool returned, details included.
			result: { content: result.content, details: result.details ?? {} },
			isError: result.isError,
		});
		return result;
	}

	async #result(call: ToolCall, signal: AbortSignal): Promise<ToolResultMessage> {
		// As in Pi, the first hook that blocks decides; the later ones are not asked.
		const event = {
			type: "tool_call",
			toolCallId: call.id,
			toolName: call.name,
			input: call.arguments,
		};
		for (const hook of this.#hooks.get("tool_call") ?? []) {
			const decision = await hook(event);
			if (isRecord(decision) && decision.block === true) {
				return errorResult(call, typeof decision.reason === "string" ? decision.reason : "blocked");
			}
		}
		const tool = this.#tools.get(call.name);
		if (tool === undefined) {
			return errorResult(call, `Tool ${call.name} not found`);
		}
		try {
			const result = await tool.execute(
				call.id,
				call.arguments,
				signal,
				undefined,
				NO_TOOL_CONTEXT,
			);
			return {
				role: "toolResult",
				toolCallId: call.id,
				toolName: call.name,
				content: result.content,
				...(isJson(result.details) ? { details: result.details } : {}),
				isError: false,
				timestamp: Date.now(),
			};
		} catch (error) {
			return errorResult(call, error instanceof Error ? error.message : String(error));
		}
	}
}

/** The second loop's session factory, with openRunSession's signature. */
export const secondLoop: RunLoop = async (_place: RunPlace, descriptor: RunDescriptor) => {
	const session = new SecondLoopSession(descriptor);
	await session.load();
	return session;
};
