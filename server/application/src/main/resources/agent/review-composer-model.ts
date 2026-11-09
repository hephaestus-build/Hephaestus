/**
 * One feedback composer conversation with the run's frozen chat model, through the gateway's model proxy. It speaks
 * the chat protocols the proxy serves and never streams. The proxy fences, bills and bounds every call.
 */

/** The chat protocols the proxy serves, by their wire names. */
export type Protocol = "anthropic-messages" | "openai-completions" | "openai-responses";

/** The model settings frozen on the run, as a composition session names them. */
export interface ModelSettings {
	protocol: Protocol;
	modelId: string;
	reasoningEffort: string | null;
	maxOutputTokens: number;
	temperature: number | null;
}

export interface Tool {
	name: string;
	description: string;
	parameters: Record<string, unknown>;
}

export interface ToolCall {
	id: string;
	name: string;
	arguments: unknown;
}

export interface ToolResult {
	callId: string;
	text: string;
}

export type Failure =
	/** The run no longer holds its lease: nothing more may be sent or stored. */
	| "OWNERSHIP_LOST"
	/** The workspace's AI choice no longer permits the model. */
	| "AI_CHOICE"
	/** The budget that funds the run is spent. */
	| "BUDGET"
	/** The run's work deadline has passed. */
	| "WORK_TIME"
	/** The provider refused the request itself, such as a revoked key or a request over the context window. */
	| "REFUSED"
	/** The provider failed or answered with something unreadable; another call may succeed. */
	| "PROVIDER"
	/** The shared endpoint could not grant capacity in time. */
	| "CAPACITY";

export interface Reply {
	text: string | null;
	toolCalls: ToolCall[];
	failure: Failure | null;
}

/** One model call's answer as the proxy returned it. */
export interface Answer {
	status: number;
	body: string;
	refusal: string | null;
}

export type Transport = (protocol: Protocol, body: string) => Promise<Answer>;

/** The proxy route of each protocol, below the proxy's base URL. */
export const PROTOCOL_PATHS: Record<Protocol, string> = {
	"anthropic-messages": "/v1/messages",
	"openai-completions": "/chat/completions",
	"openai-responses": "/responses",
};

type Json = Record<string, unknown>;

export class ModelSession {
	readonly #transport: Transport;
	readonly #settings: ModelSettings;
	readonly #system: string;
	readonly #tools: Tool[];
	readonly #history: Json[] = [];
	#calls = 0;

	constructor(transport: Transport, settings: ModelSettings, system: string, tools: Tool[]) {
		this.#transport = transport;
		this.#settings = settings;
		this.#system = system;
		this.#tools = tools;
		if (settings.protocol === "openai-completions") {
			this.#history.push({ role: "system", content: system });
		}
	}

	get calls(): number {
		return this.#calls;
	}

	/** Sends a user turn and returns the model's reply. */
	async send(text: string): Promise<Reply> {
		this.#history.push({ role: "user", content: text });
		return this.#call();
	}

	/** Sends the same request again, after a provider failure left the conversation unchanged. */
	async retry(): Promise<Reply> {
		return this.#call();
	}

	/** Answers the model's tool calls, optionally adds a user turn after them, and returns the next reply. */
	async answer(results: ToolResult[], then: string | null): Promise<Reply> {
		if (this.#settings.protocol === "anthropic-messages") {
			const content: Json[] = results.map((result) => ({
				type: "tool_result",
				tool_use_id: result.callId,
				content: result.text,
			}));
			if (then !== null) {
				content.push({ type: "text", text: then });
			}
			this.#history.push({ role: "user", content });
			return this.#call();
		}
		for (const result of results) {
			if (this.#settings.protocol === "openai-completions") {
				this.#history.push({ role: "tool", tool_call_id: result.callId, content: result.text });
			} else {
				this.#history.push({
					type: "function_call_output",
					call_id: result.callId,
					output: result.text,
				});
			}
		}
		if (then !== null) {
			this.#history.push({ role: "user", content: then });
		}
		return this.#call();
	}

	#request(): Json {
		const settings = this.#settings;
		const body: Json = { model: settings.modelId };
		const history = structuredClone(this.#history);
		if (settings.protocol === "anthropic-messages") {
			body.system = this.#system;
			body.messages = history;
			body.max_tokens = settings.maxOutputTokens;
			body.tool_choice = { type: "auto" };
			body.tools = this.#tools.map((tool) => ({
				name: tool.name,
				description: tool.description,
				input_schema: tool.parameters,
			}));
			// An opaque native model id carries no thinking-mode or sampling contract: the provider's defaults apply.
		} else if (settings.protocol === "openai-completions") {
			body.messages = history;
			body.store = false;
			body.max_completion_tokens = settings.maxOutputTokens;
			body.tools = this.#tools.map((tool) => ({
				type: "function",
				function: { name: tool.name, description: tool.description, parameters: tool.parameters },
			}));
			if (settings.reasoningEffort !== null) {
				body.reasoning_effort = settings.reasoningEffort.toLowerCase();
			}
		} else {
			body.instructions = this.#system;
			body.input = history;
			body.store = false;
			body.max_output_tokens = Math.max(16, settings.maxOutputTokens);
			body.tools = this.#tools.map((tool) => ({
				type: "function",
				name: tool.name,
				description: tool.description,
				parameters: tool.parameters,
				strict: false,
			}));
			if (settings.reasoningEffort !== null) {
				body.reasoning = { effort: settings.reasoningEffort.toLowerCase() };
				body.include = ["reasoning.encrypted_content"];
			}
		}
		if (settings.protocol !== "anthropic-messages" && settings.temperature !== null) {
			body.temperature = settings.temperature;
		}
		return body;
	}

	async #call(): Promise<Reply> {
		this.#calls += 1;
		const answer = await this.#transport(this.#settings.protocol, JSON.stringify(this.#request()));
		switch (answer.refusal) {
			case null: {
				break;
			}
			case "AI_CHOICE":
			case "BUDGET":
			case "WORK_TIME":
			case "CAPACITY": {
				return failed(answer.refusal);
			}
			case "ATTEMPT_ENDED": {
				return failed("OWNERSHIP_LOST");
			}
			// Transient, as a provider's rate limit is.
			case "CALLS_IN_FLIGHT": {
				return failed("PROVIDER");
			}
			default: {
				return failed("PROVIDER");
			}
		}
		const { status } = answer;
		if (status === 408 || status === 429 || status >= 500) {
			return failed("PROVIDER");
		}
		// A provider's client error answers the same request the same way again.
		if (status < 200 || status >= 300) {
			return failed("REFUSED");
		}
		let response: unknown;
		try {
			response = JSON.parse(answer.body);
		} catch {
			return failed("PROVIDER");
		}
		if (!isRecord(response)) {
			return failed("PROVIDER");
		}
		switch (this.#settings.protocol) {
			case "openai-completions": {
				return this.#completion(response);
			}
			case "anthropic-messages": {
				return this.#anthropic(response);
			}
			case "openai-responses": {
				return this.#responses(response);
			}
		}
	}

	#anthropic(response: Json): Reply {
		if (response.stop_reason === "refusal") {
			return { text: null, toolCalls: [], failure: "REFUSED" };
		}
		const { content } = response;
		if (!Array.isArray(content)) {
			return { text: null, toolCalls: [], failure: "PROVIDER" };
		}
		let text = "";
		const calls: ToolCall[] = [];
		for (const part of content) {
			if (!isRecord(part)) {
				continue;
			}
			if (part.type === "text") {
				text += stringOr(part.text, "");
			}
			if (part.type === "tool_use") {
				calls.push({
					id: stringOr(part.id, ""),
					name: stringOr(part.name, ""),
					arguments: part.input,
				});
			}
		}
		this.#history.push({ role: "assistant", content });
		return { text: text === "" ? null : text, toolCalls: calls, failure: null };
	}

	#completion(response: Json): Reply {
		const { choices } = response;
		const message = Array.isArray(choices) && isRecord(choices[0]) ? choices[0].message : undefined;
		if (!isRecord(message)) {
			return { text: null, toolCalls: [], failure: "PROVIDER" };
		}
		const content = typeof message.content === "string" ? message.content : null;
		const kept: Json = { role: "assistant", content };
		const calls: ToolCall[] = [];
		if (Array.isArray(message.tool_calls) && message.tool_calls.length > 0) {
			kept.tool_calls = message.tool_calls;
			for (const call of message.tool_calls) {
				const record = isRecord(call) ? call : {};
				const fn = isRecord(record.function) ? record.function : {};
				calls.push({
					id: stringOr(record.id, ""),
					name: stringOr(fn.name, ""),
					arguments: parsed(fn.arguments),
				});
			}
		}
		if (typeof message.reasoning_content === "string") {
			kept.reasoning_content = message.reasoning_content;
		}
		this.#history.push(kept);
		return { text: content, toolCalls: calls, failure: null };
	}

	#responses(response: Json): Reply {
		const { output } = response;
		if (!Array.isArray(output)) {
			return { text: null, toolCalls: [], failure: "PROVIDER" };
		}
		let text = "";
		const calls: ToolCall[] = [];
		for (const item of output) {
			if (!isRecord(item)) {
				continue;
			}
			this.#history.push(item);
			if (item.type === "message" && Array.isArray(item.content)) {
				for (const part of item.content) {
					if (isRecord(part) && part.type === "output_text") {
						text += stringOr(part.text, "");
					}
				}
			}
			if (item.type === "function_call") {
				calls.push({
					id: stringOr(item.call_id, ""),
					name: stringOr(item.name, ""),
					arguments: parsed(item.arguments),
				});
			}
		}
		return { text: text === "" ? null : text, toolCalls: calls, failure: null };
	}
}

function failed(failure: Failure): Reply {
	return { text: null, toolCalls: [], failure };
}

/** A model sends arguments as a JSON string; anything unreadable stays the raw string. */
function parsed(raw: unknown): unknown {
	if (typeof raw !== "string") {
		return raw;
	}
	try {
		const value: unknown = JSON.parse(raw);
		return value ?? raw;
	} catch {
		return raw;
	}
}

function isRecord(value: unknown): value is Json {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringOr(value: unknown, fallback: string): string {
	return typeof value === "string" ? value : fallback;
}
