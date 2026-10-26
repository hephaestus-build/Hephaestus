import { Client } from "pg";

import {
	type Api,
	type E2eLlmRoute,
	type E2ePricing,
	oneOf,
	provisionLlmModel,
	readLlmRoute,
	required,
} from "./lib/e2e-llm.ts";
import { isLoopbackHost, isSet } from "./lib/env.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { completeTransparencyNotice } from "./lib/research-consent.ts";

type JsonObject = Record<string, unknown>;

interface Config {
	provider: "github" | "gitlab";
	pat: string;
	serverUrl: string;
	llm: E2eLlmRoute;
	pricing: E2ePricing;
	workspaceSlug: string;
	accountLogin?: string;
	accountType: string;
	username: string;
	repository?: string;
	pullRequestId?: number;
	appUrl: string;
	databaseUrl: string;
}

function object(value: unknown, context: string): JsonObject {
	return asRecord(value, `${context} response`);
}

function array(value: unknown, context: string): JsonObject[] {
	return asArray(value, `${context} response`).map((entry) => object(entry, context));
}

function textField(value: JsonObject, field: string, context: string): string {
	return asString(value[field], `${context} response.${field}`);
}

function idField(value: JsonObject, context: string): number {
	const id =
		typeof value.id === "string" && /^[1-9][0-9]*$/u.test(value.id) ? Number(value.id) : value.id;
	if (typeof id !== "number" || !Number.isSafeInteger(id) || id <= 0) {
		throw new TypeError(`${context} response has no valid id`);
	}
	return id;
}

function parseArgs(args: string[]): Map<string, string> {
	const allowed = new Set([
		"--provider",
		"--server-url",
		"--llm-base-url",
		"--model",
		"--llm-protocol",
		"--llm-auth-mode",
		"--llm-pricing-mode",
		"--llm-input-usd",
		"--llm-output-usd",
		"--workspace-slug",
		"--account-login",
		"--account-type",
		"--username",
		"--repo",
		"--pr-id",
		"--app-url",
	]);
	const result = new Map<string, string>();
	for (let index = 0; index < args.length; index += 2) {
		const flag = args[index];
		const value = args[index + 1];
		if (flag === undefined || !allowed.has(flag) || value === undefined) {
			throw new Error(`invalid argument: ${flag ?? ""}`);
		}
		result.set(flag, value);
	}
	return result;
}

function nonNegativeNumber(value: string, name: string): number {
	if (!/^\d+(?:\.\d+)?$/u.test(value)) {
		throw new Error(`${name} must be a non-negative number`);
	}
	return Number(value);
}

function loopbackUrl(value: string, protocols: readonly string[], message: string): string {
	const parsed = new URL(value);
	if (!protocols.includes(parsed.protocol) || !isLoopbackHost(parsed.hostname)) {
		throw new Error(message);
	}
	return value;
}

export function loadConfig(env: Record<string, string | undefined>, args: string[]): Config {
	const flags = parseArgs(args);
	const get = (flag: string, name: string, fallback = ""): string =>
		flags.get(flag) ?? env[name] ?? fallback;
	const provider = oneOf(
		get("--provider", "E2E_PROVIDER", "gitlab"),
		["github", "gitlab"],
		"provider must be github or gitlab",
	);
	const pricingMode = oneOf(
		get("--llm-pricing-mode", "E2E_LLM_PRICING_MODE"),
		["PRICED", "NO_CHARGE"],
		"E2E_LLM_PRICING_MODE must be PRICED or NO_CHARGE",
	);
	const appUrl = loopbackUrl(
		get("--app-url", "E2E_APP_URL", "http://localhost:8080"),
		["http:"],
		"E2E_APP_URL must be a loopback URL",
	);
	const databaseUrl = loopbackUrl(
		env.E2E_DB_URL ?? "postgresql://root:root@localhost:5432/hephaestus",
		["postgres:", "postgresql:"],
		"E2E_DB_URL must be a loopback PostgreSQL URL",
	);
	const input = get("--llm-input-usd", "E2E_LLM_INPUT_USD");
	const output = get("--llm-output-usd", "E2E_LLM_OUTPUT_USD");
	const repository = get("--repo", "E2E_REPO") || undefined;
	if (repository !== undefined && !/^[A-Za-z0-9._/-]+$/u.test(repository)) {
		throw new Error("E2E_REPO contains unsupported characters");
	}
	const pullRequest = get("--pr-id", "E2E_PR_ID");
	if (pullRequest && !/^\d+$/u.test(pullRequest)) {
		throw new Error("E2E_PR_ID must be numeric");
	}
	const priceNote = env.E2E_LLM_PRICE_NOTE;
	let pricing: E2ePricing;
	if (pricingMode === "PRICED") {
		pricing = {
			pricingMode,
			per1mInputUsd: nonNegativeNumber(input, "E2E_LLM_INPUT_USD"),
			per1mOutputUsd: nonNegativeNumber(output, "E2E_LLM_OUTPUT_USD"),
		};
	} else if (isSet(priceNote)) {
		pricing = { pricingMode, note: priceNote };
	} else {
		throw new Error("E2E_LLM_PRICE_NOTE is required for NO_CHARGE");
	}
	return {
		provider,
		pat: required(
			env[provider === "github" ? "E2E_GITHUB_PAT" : "E2E_GITLAB_PAT"] ?? "",
			"an SCM PAT is required through the environment",
		),
		serverUrl: get("--server-url", "E2E_SERVER_URL", "https://gitlab.lrz.de").replace(/\/$/u, ""),
		llm: readLlmRoute(get, env),
		pricing,
		workspaceSlug: get("--workspace-slug", "E2E_WS_SLUG", "e2e"),
		accountLogin: get("--account-login", "E2E_ACCOUNT_LOGIN") || undefined,
		accountType: get("--account-type", "E2E_ACCOUNT_TYPE", "ORG"),
		username: get("--username", "E2E_USERNAME", "e2e"),
		repository,
		pullRequestId: pullRequest ? Number(pullRequest) : undefined,
		appUrl,
		databaseUrl,
	};
}

async function jsonRequest(url: string, init: RequestInit, context: string): Promise<unknown> {
	const response = await fetch(url, { ...init, signal: AbortSignal.timeout(30_000) });
	if (!response.ok) {
		throw new Error(`${context} failed with HTTP ${response.status}`);
	}
	if (response.status === 204) {
		return undefined;
	}
	return parseJson(await response.text());
}

async function login(config: Config): Promise<string> {
	const response = await fetch(`${config.appUrl}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ username: config.username, admin: true }),
		signal: AbortSignal.timeout(10_000),
	});
	if (!response.ok) {
		throw new Error("dev-login failed");
	}
	const cookie = response.headers.get("set-cookie");
	const jwt = /(?:__Host-)?HEPHAESTUS_AT=(?<token>[^;]+)/u.exec(cookie ?? "")?.groups?.token;
	if (jwt === undefined) {
		throw new Error("dev-login returned no access token");
	}
	return jwt;
}

async function assertModelListed(config: Config): Promise<void> {
	const { llm } = config;
	const llmHeaders: Record<string, string> =
		llm.authMode === "API_KEY" ? { "api-key": llm.key } : { authorization: `Bearer ${llm.key}` };
	const models = array(
		object(
			await jsonRequest(`${llm.baseUrl}/models`, { headers: llmHeaders }, "LLM model discovery"),
			"LLM model discovery",
		).data,
		"LLM model discovery",
	);
	if (!models.some((model) => model.id === llm.model)) {
		throw new Error("the configured model is not listed by the LLM provider");
	}
}

interface ScmIdentity {
	scmOrigin: string;
	scmId: number;
	scmLogin: string;
}

async function resolveScmIdentity(config: Config): Promise<ScmIdentity> {
	const scmOrigin = config.provider === "github" ? "https://github.com" : config.serverUrl;
	const scmHeaders: Record<string, string> =
		config.provider === "github"
			? { authorization: `Bearer ${config.pat}` }
			: { "private-token": config.pat };
	const scm = object(
		await jsonRequest(
			`${config.provider === "github" ? "https://api.github.com" : `${config.serverUrl}/api/v4`}/user`,
			{ headers: scmHeaders },
			"SCM identity",
		),
		"SCM identity",
	);
	const scmId = idField(scm, "SCM identity");
	const scmLogin = textField(
		scm,
		config.provider === "github" ? "login" : "username",
		"SCM identity",
	);
	if (!/^[A-Za-z0-9._-]+$/u.test(scmLogin)) {
		throw new Error("the SCM returned an unsupported login");
	}
	if (config.repository !== undefined) {
		const repository = object(
			await jsonRequest(
				config.provider === "github"
					? `https://api.github.com/repos/${config.repository}`
					: `${config.serverUrl}/api/v4/projects/${encodeURIComponent(config.repository)}`,
				{ headers: scmHeaders },
				"target repository",
			),
			"target repository",
		);
		const resolved = textField(
			repository,
			config.provider === "github" ? "full_name" : "path_with_namespace",
			"target repository",
		);
		if (resolved.toLowerCase() !== config.repository.toLowerCase()) {
			throw new Error("the SCM resolved a different target repository");
		}
	}
	return { scmOrigin, scmId, scmLogin };
}

interface IdentitySeedDatabase {
	query: (statement: string, values?: unknown[]) => Promise<{ rows: JsonObject[] }>;
}

export async function seedIdentity(
	database: IdentitySeedDatabase,
	config: Pick<Config, "provider">,
	accountId: number,
	{ scmOrigin, scmId, scmLogin }: ScmIdentity,
): Promise<number> {
	const account = await database.query(
		"SELECT EXISTS (SELECT 1 FROM account WHERE id = $1 AND status='ACTIVE') AS exists",
		[accountId],
	);
	if (account.rows[0]?.exists !== true) {
		throw new Error("E2E_APP_URL and E2E_DB_URL point to different databases");
	}
	await database.query("BEGIN");
	try {
		const provider = await database.query(
			"INSERT INTO identity_provider (type, server_url, created_at) VALUES ($1,$2,now()) ON CONFLICT (type, server_url) DO UPDATE SET server_url=EXCLUDED.server_url RETURNING id",
			[config.provider.toUpperCase(), scmOrigin],
		);
		const providerId = idField(object(provider.rows[0], "Identity provider"), "Identity provider");
		const user = await database.query(
			"INSERT INTO \"user\" (native_id, provider_id, login, type, avatar_url, html_url, created_at, updated_at) VALUES ($1,$2,$3,'USER','',$4,now(),now()) ON CONFLICT (provider_id, native_id) DO UPDATE SET login=EXCLUDED.login RETURNING id",
			[scmId, providerId, scmLogin, `${scmOrigin}/${scmLogin}`],
		);
		const userId = idField(object(user.rows[0], "SCM user"), "SCM user");
		const restrictions = await database.query(
			"SELECT EXISTS(SELECT 1 FROM identity_restriction WHERE provider_id=$1 AND provider_user_key=$2 AND team_key='' AND kind='ERASED') AS erased",
			[providerId, String(scmId)],
		);
		if (restrictions.rows[0]?.erased !== false) {
			throw new Error("SCM identity processing is suppressed");
		}
		const existing = await database.query(
			"SELECT account_id::text,disabled_at FROM identity WHERE provider_id=$1 AND provider_user_key=$2 AND team_key='' FOR UPDATE",
			[providerId, String(scmId)],
		);
		const active = existing.rows.find((row) => row.disabled_at === null);
		if (active?.account_id !== String(accountId) && existing.rows.length > 0) {
			throw new Error("SCM identity is disabled or belongs to a different account");
		}
		if (existing.rows.length === 0) {
			await database.query(
				"INSERT INTO identity (account_id, provider_id, provider_user_key, team_key, linked_at, linked_via) VALUES ($1,$2,$3,'',now(),'SIGN_IN') ON CONFLICT (provider_id, provider_user_key, team_key) WHERE disabled_at IS NULL DO NOTHING",
				[accountId, providerId, String(scmId)],
			);
		}
		const linked = await database.query(
			"SELECT account_id::text FROM identity WHERE provider_id=$1 AND provider_user_key=$2 AND team_key='' AND disabled_at IS NULL",
			[providerId, String(scmId)],
		);
		if (linked.rows[0]?.account_id !== String(accountId)) {
			throw new Error("SCM identity is disabled or belongs to a different account");
		}
		await database.query("COMMIT");
		return userId;
	} catch (error) {
		await database.query("ROLLBACK");
		throw error;
	}
}

async function ensureWorkspace(
	api: Api,
	config: Config,
	accountLogin: string,
	scmOrigin: string,
): Promise<JsonObject> {
	let workspace = array(await api("GET", "/workspaces"), "workspaces").find(
		(entry) => entry.workspaceSlug === config.workspaceSlug,
	);
	if (workspace) {
		if (
			typeof workspace.accountLogin !== "string" ||
			workspace.accountLogin.toLowerCase() !== accountLogin.toLowerCase() ||
			workspace.providerType !== config.provider.toUpperCase()
		) {
			throw new Error("workspace slug belongs to a different SCM account or provider");
		}
		if (config.provider === "gitlab") {
			const detail = object(await api("GET", `/workspaces/${config.workspaceSlug}`), "workspace");
			if (
				typeof detail.serverUrl !== "string" ||
				detail.serverUrl.replace(/\/$/u, "") !== scmOrigin
			) {
				throw new Error("workspace slug belongs to a different SCM server");
			}
		}
	} else {
		await api("POST", "/workspaces", {
			workspaceSlug: config.workspaceSlug,
			displayName: "E2E Practice Detection",
			accountLogin,
			accountType: config.accountType,
			kind: config.provider.toUpperCase(),
			personalAccessToken: config.pat,
			serverUrl: scmOrigin,
		});
		workspace = array(await api("GET", "/workspaces"), "workspaces").find(
			(entry) => entry.workspaceSlug === config.workspaceSlug,
		);
	}
	if (!workspace) {
		throw new Error("workspace create/lookup failed");
	}
	return workspace;
}

async function configureLlm(api: Api, config: Config): Promise<number> {
	const settings = object(await api("GET", "/admin/llm/settings"), "LLM settings");
	if (settings.allowWorkspaceConnections !== true) {
		await api("PUT", "/admin/llm/settings", { allowWorkspaceConnections: true });
	}
	const modelId = await provisionLlmModel(
		api,
		`/workspaces/${config.workspaceSlug}/llm`,
		{
			connection: { slug: "e2e-openai-compatible", displayName: "E2E OpenAI-compatible" },
			modelDisplayName: "E2E model",
			pricing: config.pricing,
		},
		config.llm,
	);
	return modelId;
}

async function bindAgents(api: Api, config: Config, modelId: number): Promise<void> {
	for (const purpose of ["PRACTICE_REVIEW", "MENTOR"]) {
		await api("PUT", `/workspaces/${config.workspaceSlug}/agents/${purpose}`, {
			workspaceModelId: modelId,
			enabled: true,
			// Heph stops an answer after 300 seconds (AgentBindingLimits.MAX_HEPH_TURN_SECONDS).
			timeoutSeconds: purpose === "MENTOR" ? 300 : 1200,
			maxConcurrentJobs: 1,
		});
	}
	const agents = array(
		await api("GET", `/workspaces/${config.workspaceSlug}/agents`),
		"agent bindings",
	);
	if (agents.filter((agent) => agent.ready === true).length !== 2) {
		throw new Error("both agent purposes must be ready");
	}
}

export async function definePractices(
	api: Api,
	config: Pick<Config, "workspaceSlug">,
): Promise<void> {
	const needs = [
		{ source: "scm.pull-request.core", need: "REQUIRED" },
		{ source: "scm.pull-request.diff", need: "REQUIRED" },
		{ source: "scm.pull-request.comments", need: "REQUIRED" },
	];
	const definitions = [
		{
			slug: "submit-reviewable-work",
			name: "Submit reviewable work",
			events: [
				"scm.pull_request.opened",
				"scm.pull_request.ready",
				"scm.pull_request.synchronized",
			],
			criteria:
				"The MR is appropriately scoped, has a clear description, passes CI, and is not a draft dump. Flag oversized or unfocused MRs and missing descriptions.",
		},
		{
			slug: "act-on-feedback",
			name: "Act on feedback",
			events: ["scm.pull_request.synchronized"],
			criteria:
				"After reviewers leave comments, the author addresses them with follow-up commits or replies rather than ignoring or force-resolving them.",
		},
		{
			slug: "plan-and-scope-issues",
			name: "Plan & scope issues",
			events: ["scm.pull_request.opened"],
			criteria:
				"The MR references a well-defined, properly scoped issue and stays within that scope. Flag MRs with no linked issue or scope creep.",
		},
	];
	const existingPractices = array(
		await api("GET", `/workspaces/${config.workspaceSlug}/practices`),
		"practices",
	);
	for (const practice of existingPractices) {
		await api(
			"PATCH",
			`/workspaces/${config.workspaceSlug}/practices/${textField(practice, "slug", "practice")}/feedback-mode`,
			{ feedbackMode: "OFF" },
		);
	}
	for (const entry of definitions) {
		const definition = {
			name: entry.name,
			reviewOn: { events: entry.events, state: {} },
			appliesWhen: null,
			about: "AUTHOR",
			reads: { contract: "2.0.0", sources: needs },
			reviewedBy: { by: "HEPHAESTUS", humanReason: null, limits: [] },
			criteria: entry.criteria,
			script: null,
			scan: null,
			lineNotes: false,
			guidance: {
				whyItMatters: null,
				whatGoodLooksLike: null,
				goingWell: null,
				visual: null,
				guide: null,
			},
		};
		const existing = existingPractices.find((practice) => practice.slug === entry.slug);
		await (existing === undefined
			? api("POST", `/workspaces/${config.workspaceSlug}/practices`, {
					slug: entry.slug,
					kind: "scm.pull_request",
					definition,
					groupSlug: null,
					position: null,
				})
			: api("PATCH", `/workspaces/${config.workspaceSlug}/practices/${entry.slug}`, {
					definition,
					expectedVersionId: textField(
						object(existing.currentVersion, "practice.currentVersion"),
						"id",
						"practice.currentVersion",
					),
				}));
		await api(
			"PATCH",
			`/workspaces/${config.workspaceSlug}/practices/${entry.slug}/feedback-mode`,
			{ feedbackMode: "AUTOMATIC" },
		);
	}
}

async function selectPullRequest(
	database: Client,
	config: Config,
	workspaceId: number,
): Promise<number | undefined> {
	const parameters: unknown[] = [workspaceId];
	let pullRequestScope =
		"FROM issue i JOIN repository r ON r.id=i.repository_id JOIN repository_to_monitor rtm ON rtm.workspace_id=$1 AND lower(rtm.name_with_owner)=lower(r.name_with_owner) AND (rtm.native_id IS NULL OR rtm.native_id=r.native_id) WHERE i.issue_type='PULL_REQUEST'";
	if (config.repository !== undefined) {
		parameters.push(config.repository);
		pullRequestScope += ` AND lower(r.name_with_owner)=lower($${parameters.length})`;
	}
	if (config.pullRequestId === undefined) {
		const result = await database.query<{ id: number }>(
			`SELECT i.id ${pullRequestScope} ORDER BY i.updated_at DESC NULLS LAST, i.id DESC LIMIT 1`,
			parameters,
		);
		return result.rows[0]?.id;
	}
	parameters.push(config.pullRequestId);
	const result = await database.query<{ exists: boolean }>(
		`SELECT EXISTS (SELECT 1 ${pullRequestScope} AND i.id=$${parameters.length}) AS exists`,
		parameters,
	);
	if (result.rows[0]?.exists !== true) {
		throw new Error("E2E_PR_ID is not a pull request monitored by the target workspace");
	}
	return config.pullRequestId;
}

async function main(): Promise<void> {
	const config = loadConfig(process.env, process.argv.slice(2));
	await assertModelListed(config);
	let jwt = await login(config);
	const api: Api = async (method, path, body, headers) =>
		jsonRequest(
			`${config.appUrl}${path}`,
			{
				method,
				headers: {
					authorization: `Bearer ${jwt}`,
					...(body === undefined ? {} : { "content-type": "application/json" }),
					...headers,
				},
				body: body === undefined ? undefined : JSON.stringify(body),
			},
			path,
		);
	await completeTransparencyNotice(api);
	const accountId = idField(object(await api("GET", "/user"), "user"), "user");
	const scm = await resolveScmIdentity(config);
	const database = new Client({
		connectionString: config.databaseUrl,
		connectionTimeoutMillis: 5000,
	});
	await database.connect();
	try {
		await seedIdentity(database, config, accountId, scm);
		jwt = await login(config);
		const workspace = await ensureWorkspace(
			api,
			config,
			config.accountLogin ?? scm.scmLogin,
			scm.scmOrigin,
		);
		const workspaceId = idField(workspace, "workspace");
		await api("PATCH", `/workspaces/${config.workspaceSlug}/token`, {
			personalAccessToken: config.pat,
		});
		await database.query(
			"INSERT INTO membership (workspace_id, account_id, role, hidden, joined_at) VALUES ($1,$2,'ADMIN',false,now()) ON CONFLICT (workspace_id,account_id) DO UPDATE SET left_at=NULL,role=CASE WHEN membership.role='OWNER' THEN 'OWNER' ELSE 'ADMIN' END",
			[workspaceId, accountId],
		);
		await api("PATCH", `/workspaces/${config.workspaceSlug}/features`, {
			practicesEnabled: true,
			practiceReviewAutoTriggerEnabled: true,
			practiceReviewManualTriggerEnabled: true,
		});
		const reviewSettingsPath = `/workspaces/${config.workspaceSlug}/practices/review-settings`;
		const reviewSettings = object(await api("GET", reviewSettingsPath), "practice-review settings");
		await api(
			"PATCH",
			reviewSettingsPath,
			{ cooldownMinutes: 0 },
			{
				"if-match": textField(reviewSettings, "etag", "practice-review settings"),
			},
		);
		const modelId = await configureLlm(api, config);
		await bindAgents(api, config, modelId);
		await definePractices(api, config);
		if (config.repository !== undefined) {
			const monitored = await api("GET", `/workspaces/${config.workspaceSlug}/repositories`);
			if (!Array.isArray(monitored) || !monitored.includes(config.repository)) {
				await api(
					"POST",
					`/workspaces/${config.workspaceSlug}/repositories?nameWithOwner=${encodeURIComponent(config.repository)}`,
				);
			}
		}
		const selectedPullRequestId = await selectPullRequest(database, config, workspaceId);
		console.log(
			`✓ E2E ready. Workspace ${config.workspaceSlug} (id ${workspaceId}) is configured${selectedPullRequestId === undefined ? "" : ` for PR ${selectedPullRequestId}`}.`,
		);
	} catch (error) {
		await database.query("ROLLBACK").catch(() => undefined);
		throw error;
	} finally {
		await database.end();
	}
}

if (import.meta.main) {
	await main();
}
