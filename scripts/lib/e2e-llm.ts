/**
 * The OpenAI-compatible model an E2E run talks to, read and provisioned once for every caller:
 * `scripts/e2e-setup.ts` and the live Playwright specs. One reader keeps the defaults the same,
 * and one provisioner refreshes the key and refuses a changed route on every run.
 */
import { asArray, asRecord } from "./json.ts";

export const LLM_PROTOCOLS = ["openai-completions", "openai-responses"] as const;
export const LLM_AUTH_MODES = ["BEARER", "API_KEY"] as const;

export interface E2eLlmRoute {
	/** Without a trailing slash, as the server stores it. */
	baseUrl: string;
	key: string;
	model: string;
	protocol: (typeof LLM_PROTOCOLS)[number];
	authMode: (typeof LLM_AUTH_MODES)[number];
}

/** A value from a command-line flag or the environment; `name` is the variable. */
export type Setting = (flag: string, name: string, fallback?: string) => string;

export function oneOf<const T extends string>(
	value: string,
	allowed: readonly T[],
	message: string,
): T {
	const match = allowed.find((candidate) => candidate === value);
	if (match === undefined) {
		throw new Error(message);
	}
	return match;
}

export function required(value: string, message: string): string {
	if (!value) {
		throw new Error(message);
	}
	return value;
}

/** The route from `E2E_LLM_BASE_URL`, `E2E_LLM_KEY`, `E2E_MODEL`, `E2E_LLM_PROTOCOL` and `E2E_LLM_AUTH_MODE`. */
export function readLlmRoute(get: Setting, env: Record<string, string | undefined>): E2eLlmRoute {
	return {
		baseUrl: required(
			get("--llm-base-url", "E2E_LLM_BASE_URL"),
			"E2E_LLM_BASE_URL is required",
		).replace(/\/$/u, ""),
		// The key comes from the environment only, so it never shows in a process list.
		key: required(env.E2E_LLM_KEY ?? "", "E2E_LLM_KEY is required"),
		model: required(get("--model", "E2E_MODEL"), "E2E_MODEL is required"),
		protocol: oneOf(
			get("--llm-protocol", "E2E_LLM_PROTOCOL", "openai-responses"),
			LLM_PROTOCOLS,
			"E2E_LLM_PROTOCOL is invalid",
		),
		authMode: oneOf(
			get("--llm-auth-mode", "E2E_LLM_AUTH_MODE", "BEARER"),
			LLM_AUTH_MODES,
			"E2E_LLM_AUTH_MODE is invalid",
		),
	};
}

/** A call to the Hephaestus API as an admin; it rejects on a status that is not 2xx. */
export type Api = (
	method: string,
	path: string,
	body?: unknown,
	headers?: Record<string, string>,
) => Promise<unknown>;

function idOf(value: unknown, context: string): number {
	const { id } = asRecord(value, `${context} response`);
	if (typeof id !== "number" || !Number.isSafeInteger(id)) {
		throw new TypeError(`${context} response has no valid id`);
	}
	return id;
}

/** Where the catalog lives: the instance's, or one workspace's own. */
export type LlmPath = "/admin/llm" | `/workspaces/${string}/llm`;

/** What the model costs; a NO_CHARGE model says why in its note. */
export type E2ePricing =
	| { pricingMode: "PRICED"; per1mInputUsd: number; per1mOutputUsd: number }
	| { pricingMode: "NO_CHARGE"; note: string };

/**
 * Makes the connection `slug` under `llmPath` (`/admin/llm` or `/workspaces/{slug}/llm`) serve
 * `route`, and returns its id. A connection from an earlier run is reused only when its base URL,
 * protocol and auth mode match, since the server does not let them change. The key is set on every
 * run, so a rotated key takes effect. The connection must then reach the provider.
 */
export async function provisionLlmConnection(
	api: Api,
	llmPath: LlmPath,
	{ slug, displayName }: { slug: string; displayName: string },
	route: E2eLlmRoute,
): Promise<number> {
	const connections = asArray(await api("GET", `${llmPath}/connections`), "LLM connections").map(
		(entry) => asRecord(entry, "LLM connection"),
	);
	const existing = connections.find((entry) => entry.slug === slug);
	if (
		existing !== undefined &&
		(existing.baseUrl !== route.baseUrl ||
			existing.apiProtocol !== route.protocol ||
			existing.authMode !== route.authMode)
	) {
		throw new Error(
			`the existing ${slug} connection uses a different base URL, protocol or auth mode; delete it or use a fresh workspace`,
		);
	}
	const connectionId = idOf(
		existing ??
			(await api("POST", `${llmPath}/connections`, {
				displayName,
				slug,
				baseUrl: route.baseUrl,
				apiProtocol: route.protocol,
				authMode: route.authMode,
				apiKey: route.key,
				enabled: false,
			})),
		"LLM connection",
	);
	await api("PATCH", `${llmPath}/connections/${connectionId}`, { apiKey: route.key });
	const probe = asRecord(
		await api("POST", `${llmPath}/connections/${connectionId}/probe`),
		"LLM probe response",
	);
	if (probe.reachable !== true) {
		throw new Error(`the ${slug} connection cannot reach the model provider`);
	}
	await api("PATCH", `${llmPath}/connections/${connectionId}`, { enabled: true });
	return connectionId;
}

/**
 * Makes `route.model` ready to bind on the connection that `provisionLlmConnection` keeps, and
 * returns the model's id. Every run sets the price, enables the model and, in the instance catalog,
 * shares it with every workspace. A model that a partial earlier run left disabled, unpriced or
 * unshared is repaired too, not only a new one.
 */
export async function provisionLlmModel(
	api: Api,
	llmPath: LlmPath,
	setup: {
		connection: { slug: string; displayName: string };
		modelDisplayName: string;
		pricing: E2ePricing;
	},
	route: E2eLlmRoute,
): Promise<number> {
	const connectionId = await provisionLlmConnection(api, llmPath, setup.connection, route);
	const instance = llmPath === "/admin/llm";
	// The instance reprices through its own endpoint; a workspace model carries its price as fields.
	const { pricing } = setup;
	const workspacePricing =
		pricing.pricingMode === "PRICED"
			? pricing
			: { pricingMode: pricing.pricingMode, priceNote: pricing.note };
	const existing = asArray(await api("GET", `${llmPath}/models`), "LLM models")
		.map((entry) => asRecord(entry, "LLM model"))
		.find((entry) => entry.connectionId === connectionId && entry.upstreamModelId === route.model);
	const modelId = idOf(
		existing ??
			(await api("POST", `${llmPath}/connections/${connectionId}/models`, {
				displayName: setup.modelDisplayName,
				upstreamModelId: route.model,
				enabled: false,
				...(instance ? {} : workspacePricing),
			})),
		"LLM model",
	);
	if (instance) {
		await api("PUT", `${llmPath}/models/${modelId}/price`, pricing);
		await api("PATCH", `${llmPath}/models/${modelId}`, { enabled: true });
		await api("PUT", `${llmPath}/models/${modelId}/sharing`, { visibility: "PUBLIC" });
	} else {
		await api("PATCH", `${llmPath}/models/${modelId}`, { enabled: true, ...workspacePricing });
	}
	return modelId;
}
