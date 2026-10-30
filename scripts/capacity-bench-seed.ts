/** TEST ONLY. Provision synthetic sessions from the driver in the isolated server Pod. */
import { readFile, writeFile } from "node:fs/promises";

import { provisionLlmModel, type Api } from "./lib/e2e-llm.ts";
import { asRecord, asString, asStringArray, parseJson } from "./lib/json.ts";
import { completeTransparencyNotice } from "./lib/research-consent.ts";

const base = "http://127.0.0.1:8080";
const workspace = "capacity-bench-course";
const sessions: string[] = [];
let admin = "";
const users = Number(process.env.CAPACITY_USERS ?? "40");
if (!Number.isSafeInteger(users) || users < 1 || users > 120) {
	throw new Error("CAPACITY_USERS must be 1..120");
}

for (let index = 0; process.env.CAPACITY_SEED_MODE !== "models" && index < users; index += 1) {
	const response = await fetch(`${base}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({
			username: `capacity-bench-${String(index).padStart(3, "0")}`,
			admin: index === 0,
		}),
	});
	await response.arrayBuffer();
	const token = /HEPHAESTUS_AT=(?<token>[^;]+)/u.exec(response.headers.get("set-cookie") ?? "")
		?.groups?.token;
	if (!response.ok || token === undefined) {
		throw new Error(`Synthetic login ${index} failed: HTTP ${response.status}`);
	}
	sessions.push(token);
	if (index === 0) {
		admin = token;
	}
	await completeTransparencyNotice(async (method, path, body) =>
		apiWithToken(token, method, path, body),
	);
}

if (process.env.CAPACITY_SEED_MODE === "models") {
	sessions.push(
		...asStringArray(parseJson(await readFile("/bench/auth-tokens.json", "utf8")), "sessions"),
	);
	admin = sessions[0] ?? "";
}

await writeFile("/bench/auth-tokens.json", JSON.stringify(sessions), { mode: 0o600 });
await writeFile(
	"/bench/seed-count.json",
	JSON.stringify({ syntheticSessions: sessions.length, workspace }),
);
if (process.env.CAPACITY_SEED_MODE === "sessions") {
	process.stdout.write(
		`${users} synthetic sessions provisioned; credentials stored only in /bench\n`,
	);
} else {
	const api: Api = async (method, path, body) => apiWithToken(admin, method, path, body);
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
			baseUrl: "http://replay.capacity-bench-20261030.svc.cluster.local:8080/v1",
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
			timeoutSeconds: purpose === "MENTOR" ? 300 : 1200,
			maxConcurrentJobs: 1,
		});
	}
	process.stdout.write("Synthetic replay model bound to both purposes in the isolated workspace\n");
}

async function apiWithToken(
	token: string,
	method: string,
	path: string,
	body?: unknown,
): Promise<unknown> {
	const response = await fetch(`${base}${path}`, {
		method,
		headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
		body: body === undefined ? undefined : JSON.stringify(body),
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
}
