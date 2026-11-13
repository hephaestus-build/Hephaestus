/**
 * TEST ONLY. The synthetic sign-in and the fake model binding of the capacity bench, on a server
 * with the e2e sign-in. `scripts/capacity-bench-seed.ts` and the turn-roll drill of
 * `scripts/kubernetes-cluster.ts` use them.
 */
import { provisionLlmModel, type Api } from "./e2e-llm.ts";
import { asRecord, asString, parseJson } from "./json.ts";
import { completeTransparencyNotice } from "./research-consent.ts";

/** A call to the server at `base` with `token`; it rejects on a status that is not 2xx. */
export function syntheticApi(base: string, token: string): Api {
	return async (method, path, body) => {
		const response = await fetch(`${base}${path}`, {
			method,
			headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
			...(body === undefined ? {} : { body: JSON.stringify(body) }),
		});
		const raw = await response.text();
		if (!response.ok) {
			throw new Error(`Synthetic setup ${method} ${path}: HTTP ${response.status}`);
		}
		if (raw === "") {
			return null;
		}
		const value = parseJson(raw);
		if (path === "/user/consent" && method === "GET") {
			asString(asRecord(value, "consent").noticeVersion, "noticeVersion");
		}
		return value;
	};
}

/** Signs `username` in through the e2e sign-in, completes the notice, and returns the session token. */
export async function syntheticSession(
	base: string,
	username: string,
	admin: boolean,
): Promise<string> {
	const response = await fetch(`${base}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ username, admin }),
	});
	await response.arrayBuffer();
	const token = /HEPHAESTUS_AT=(?<token>[^;]+)/u.exec(response.headers.get("set-cookie") ?? "")
		?.groups?.token;
	if (!response.ok || token === undefined) {
		throw new Error(`Synthetic login ${username} failed: HTTP ${response.status}`);
	}
	const api = syntheticApi(base, token);
	await completeTransparencyNotice(async (method, path, body) => api(method, path, body));
	return token;
}

/**
 * Releases Silent Mode through the instance-admin API, as an operator does, and returns whether it is still engaged.
 * A new release starts with Silent Mode on, and it suppresses every outbound post.
 */
export async function releaseSilentMode(base: string, token: string): Promise<boolean> {
	const settings = async (
		path: string,
		method: string,
		extra: Record<string, string> = {},
		body?: string,
	) => {
		const response = await fetch(`${base}/admin/settings${path}`, {
			method,
			headers: { authorization: `Bearer ${token}`, "content-type": "application/json", ...extra },
			...(body === undefined ? {} : { body }),
		});
		const raw = await response.text();
		if (!response.ok) {
			throw new Error(`Silent Mode ${method}: HTTP ${response.status}`);
		}
		return asRecord(parseJson(raw), "instance settings");
	};
	const current = await settings("", "GET");
	if (current.silentModeEngaged !== false) {
		// Releasing needs the current ETag, so that it cannot overwrite a change that it has not seen.
		await settings(
			"/silent-mode",
			"PATCH",
			{ "if-match": asString(current.etag, "settings etag") },
			JSON.stringify({ engaged: false }),
		);
	}
	const released = await settings("", "GET");
	return released.silentModeEngaged !== false;
}

/**
 * Binds the fake provider at `baseUrl` to conversations and reviews in `workspace`, as the
 * workspace's own model: no instance model and no real provider credential.
 */
export async function bindReplayModel(
	api: Api,
	workspace: string,
	baseUrl: string,
	conversationTimeoutSeconds: number,
): Promise<void> {
	await api("PUT", "/admin/llm/settings", { allowWorkspaceConnections: true });
	const id = await provisionLlmModel(
		api,
		`/workspaces/${workspace}/llm`,
		{
			connection: { slug: "capacity-replay", displayName: "Synthetic capacity replay" },
			modelDisplayName: "Synthetic capacity replay",
			pricing: { pricingMode: "NO_CHARGE", note: "Isolated deterministic benchmark provider" },
		},
		{
			baseUrl,
			key: "synthetic-fixture",
			model: "capacity-replay",
			protocol: "openai-completions",
			authMode: "BEARER",
		},
	);
	await api("PATCH", `/workspaces/${workspace}/llm/models/${id}`, {
		contextWindow: 128_000,
		maxOutputTokens: 8192,
		operatedBy: "OWN_ORGANISATION",
	});
	for (const purpose of ["MENTOR", "PRACTICE_REVIEW"]) {
		await api("DELETE", `/workspaces/${workspace}/agents/${purpose}?dataHandlingTier=UNDECLARED`);
		await api("PUT", `/workspaces/${workspace}/agents/${purpose}?dataHandlingTier=IN_HOUSE`, {
			workspaceModelId: id,
			enabled: true,
			timeoutSeconds: purpose === "MENTOR" ? conversationTimeoutSeconds : 1200,
		});
	}
}
