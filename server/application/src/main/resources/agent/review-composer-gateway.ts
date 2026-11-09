import {
	type Answer,
	type ModelSettings,
	PROTOCOL_PATHS,
	type Protocol,
	type Tool,
} from "./review-composer-model.ts";

/**
 * The composition routes and the model proxy of one `review_compose` run, as the gateway serves them. The server renders
 * each session and runs every tool; what it answers is checked here before the loop reads it.
 */

export type Destination = "ON_WORK" | "PRACTICE_PAGE" | "CONVERSATION";

export const DESTINATIONS: readonly Destination[] = ["ON_WORK", "PRACTICE_PAGE", "CONVERSATION"];

/** Why a destination ended without composed feedback, as the server's `CompositionStop` names it. */
export type Stop =
	| "NO_AI"
	| "BUDGET"
	| "DEADLINE"
	| "MODEL_REFUSED"
	| "CONTEXT_WINDOW"
	| "PUBLICATION_REFUSED"
	| "NO_DECISION"
	| "STEP_LIMIT"
	| "INPUTS_GONE"
	| "INPUTS_ERASED"
	| "PROVIDER"
	| "CAPACITY"
	| "UNEXPECTED";

const STOPS: readonly Stop[] = [
	"NO_AI",
	"BUDGET",
	"DEADLINE",
	"MODEL_REFUSED",
	"CONTEXT_WINDOW",
	"PUBLICATION_REFUSED",
	"NO_DECISION",
	"STEP_LIMIT",
	"INPUTS_GONE",
	"INPUTS_ERASED",
	"PROVIDER",
	"CAPACITY",
	"UNEXPECTED",
];

/** A cause that another attempt may compose past, which hands the run on; the server also observes routing. */
export type Passing = "DEADLINE" | "PROVIDER" | "CAPACITY" | "ROUTING_UNAVAILABLE" | "UNEXPECTED";

const PASSING: readonly Passing[] = [
	"DEADLINE",
	"PROVIDER",
	"CAPACITY",
	"ROUTING_UNAVAILABLE",
	"UNEXPECTED",
];

export interface Session {
	destination: Destination;
	model: ModelSettings;
	system: string;
	input: string;
	tools: Tool[];
	finish: string | null;
	nudge: string;
	accepted: boolean;
	fits: boolean;
	calls: number;
	publicationContext: string | null;
}

/** The next session, or the cause that stops every destination, with the composition's deadline. */
export interface Next {
	session: Session | null;
	unavailable: Stop | null;
	deadline: string;
	lastAttempt: boolean;
}

export type Ending = "STORED" | "RECOMPOSE" | "STOPPED";

export interface ToolAnswer {
	text: string;
	published: boolean;
	ending: Ending | null;
	stop: Stop | null;
}

/** How the run's gateway is reached: its composition routes, its model proxy and the headers that name the run. */
export interface Gateway {
	/** The run's composition routes, such as `http://server:8081/internal/agent/runs/<run>/composition`. */
	compositionUrl: string;
	/** The model proxy, such as `http://server:8081/internal/llm`. */
	proxyUrl: string;
	headers: Record<string, string>;
	fetch?: typeof fetch;
	/** The first pause after a provider failure; each later one doubles. */
	retryPauseMs?: number;
	/** The driver aborts it on SIGTERM or when it may not renew the run's lease; the loop stops at its next await. */
	signal?: AbortSignal;
}

/** The composition cannot go on: the run lost its lease, or the server no longer holds the review's inputs. */
export class CompositionHaltError extends Error {
	readonly halt: "LEASE_LOST" | "INPUTS_GONE" | "INPUTS_ERASED";

	constructor(halt: "LEASE_LOST" | "INPUTS_GONE" | "INPUTS_ERASED") {
		super(halt);
		this.name = "CompositionHaltError";
		this.halt = halt;
	}
}

export interface Client {
	next: (finished: Iterable<Destination>) => Promise<Next | null>;
	tool: (
		name: string,
		call: {
			destination: Destination;
			callId: string;
			arguments: unknown;
			publicationContext: string | null;
		},
		deadline: string,
	) => Promise<ToolAnswer>;
	stop: (reason: Stop | "COMPLETE") => Promise<void>;
	close: (stops: Map<Destination, Stop>) => Promise<Passing | null>;
	model: (protocol: Protocol, body: string) => Promise<Answer>;
}

export function compositionClient(gateway: Gateway): Client {
	const send = gateway.fetch ?? globalThis.fetch;
	const headers = { ...gateway.headers, "content-type": "application/json" };

	async function post(path: string, body: unknown): Promise<Response> {
		const response = await send(gateway.compositionUrl + path, {
			method: "POST",
			headers,
			body: JSON.stringify(body),
			signal: gateway.signal,
		});
		if (response.status === 409) {
			throw new CompositionHaltError("LEASE_LOST");
		}
		if (response.status === 410) {
			const detail = await response.text();
			throw new CompositionHaltError(
				detail.includes("INPUTS_ERASED") ? "INPUTS_ERASED" : "INPUTS_GONE",
			);
		}
		if (!response.ok) {
			throw new Error(`The composition route ${path} answered ${response.status}`);
		}
		return response;
	}

	return {
		async next(finished) {
			const response = await post("/next", { finished: [...finished] });
			return response.status === 204 ? null : asNext(await json(response));
		},
		async tool(name, call, deadline) {
			const response = await post(`/tools/${encodeURIComponent(name)}`, { ...call, deadline });
			return asToolAnswer(await json(response));
		},
		async stop(reason) {
			await post("/stop", { reason });
		},
		async close(stops) {
			const response = await post("/close", { stops: Object.fromEntries(stops) });
			const answer = await json(response);
			if (!isRecord(answer)) {
				return fail("close");
			}
			const { handedOn } = answer;
			return handedOn === null
				? null
				: (PASSING.find((cause) => cause === handedOn) ?? fail("hand-on cause"));
		},
		async model(protocol, body) {
			let response: Response;
			try {
				response = await send(gateway.proxyUrl + PROTOCOL_PATHS[protocol], {
					method: "POST",
					headers,
					body,
					signal: gateway.signal,
				});
			} catch (error) {
				if (gateway.signal?.aborted === true) {
					throw error;
				}
				// A connection the gateway dropped is a provider failure the loop may retry.
				return { status: 503, body: "", refusal: null };
			}
			const text = await response.text().catch(() => "");
			return {
				status: response.status,
				body: text,
				refusal: response.headers.get("Hephaestus-Proxy-Refusal"),
			};
		},
	};
}

// ------------------------------------------------------------ what the gateway answers

async function json(response: Response): Promise<unknown> {
	const body: unknown = await response.json();
	return body;
}

function asNext(value: unknown): Next {
	if (
		!isRecord(value) ||
		typeof value.deadline !== "string" ||
		typeof value.lastAttempt !== "boolean"
	) {
		return fail("next");
	}
	return {
		session: value.session === null ? null : asSession(value.session),
		unavailable: asStop(value.unavailable),
		deadline: value.deadline,
		lastAttempt: value.lastAttempt,
	};
}

function asSession(value: unknown): Session {
	if (
		!isRecord(value) ||
		!isDestination(value.destination) ||
		!isRecord(value.model) ||
		typeof value.system !== "string" ||
		typeof value.input !== "string" ||
		!Array.isArray(value.tools) ||
		!isNullableString(value.finish) ||
		typeof value.nudge !== "string" ||
		typeof value.accepted !== "boolean" ||
		typeof value.fits !== "boolean" ||
		typeof value.calls !== "number" ||
		!isNullableString(value.publicationContext)
	) {
		return fail("session");
	}
	const { model } = value;
	if (
		!isProtocol(model.protocol) ||
		typeof model.modelId !== "string" ||
		!isNullableString(model.reasoningEffort) ||
		typeof model.maxOutputTokens !== "number" ||
		!(model.temperature === null || typeof model.temperature === "number")
	) {
		return fail("session model");
	}
	return {
		destination: value.destination,
		model: {
			protocol: model.protocol,
			modelId: model.modelId,
			reasoningEffort: model.reasoningEffort,
			maxOutputTokens: model.maxOutputTokens,
			temperature: model.temperature,
		},
		system: value.system,
		input: value.input,
		tools: value.tools.map(asTool),
		finish: value.finish,
		nudge: value.nudge,
		accepted: value.accepted,
		fits: value.fits,
		calls: value.calls,
		publicationContext: value.publicationContext,
	};
}

function asTool(value: unknown): Tool {
	if (
		!isRecord(value) ||
		typeof value.name !== "string" ||
		typeof value.description !== "string" ||
		!isRecord(value.parameters)
	) {
		return fail("tool");
	}
	return { name: value.name, description: value.description, parameters: value.parameters };
}

function asToolAnswer(value: unknown): ToolAnswer {
	if (!isRecord(value) || typeof value.text !== "string" || typeof value.published !== "boolean") {
		return fail("tool answer");
	}
	const { ending } = value;
	if (!(ending === null || ending === "STORED" || ending === "RECOMPOSE" || ending === "STOPPED")) {
		return fail("tool ending");
	}
	return { text: value.text, published: value.published, ending, stop: asStop(value.stop) };
}

function asStop(value: unknown): Stop | null {
	if (value === null) {
		return null;
	}
	return STOPS.find((stop) => stop === value) ?? fail("stop");
}

function isDestination(value: unknown): value is Destination {
	return value === "ON_WORK" || value === "PRACTICE_PAGE" || value === "CONVERSATION";
}

function isProtocol(value: unknown): value is Protocol {
	return (
		value === "anthropic-messages" || value === "openai-completions" || value === "openai-responses"
	);
}

function isNullableString(value: unknown): value is string | null {
	return value === null || typeof value === "string";
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function fail(what: string): never {
	throw new Error(`The gateway answered an unreadable ${what}`);
}
