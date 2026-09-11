import { Client } from "pg";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";

type JsonObject = Record<string, unknown>;

interface Config {
	provider: "github" | "gitlab";
	pat: string;
	serverUrl: string;
	llmKey: string;
	llmBaseUrl: string;
	model: string;
	protocol: "openai-completions" | "openai-responses";
	authMode: "BEARER" | "API_KEY";
	pricingMode: "PRICED" | "NO_CHARGE";
	inputUsd?: number;
	outputUsd?: number;
	priceNote?: string;
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
	if (typeof value.id !== "number" || !Number.isSafeInteger(value.id))
		throw new Error(`${context} response has no valid id`);
	return value.id;
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
		if (!flag || !allowed.has(flag) || value === undefined)
			throw new Error(`invalid argument: ${flag ?? ""}`);
		result.set(flag, value);
	}
	return result;
}

export function loadConfig(env: Record<string, string | undefined>, args: string[]): Config {
	const flags = parseArgs(args);
	const get = (flag: string, name: string, fallback = ""): string =>
		flags.get(flag) ?? env[name] ?? fallback;
	const provider = get("--provider", "E2E_PROVIDER", "gitlab");
	if (provider !== "github" && provider !== "gitlab")
		throw new Error("provider must be github or gitlab");
	const protocol = get("--llm-protocol", "E2E_LLM_PROTOCOL", "openai-completions");
	if (protocol !== "openai-completions" && protocol !== "openai-responses")
		throw new Error("E2E_LLM_PROTOCOL is invalid");
	const authMode = get("--llm-auth-mode", "E2E_LLM_AUTH_MODE", "BEARER");
	if (authMode !== "BEARER" && authMode !== "API_KEY")
		throw new Error("E2E_LLM_AUTH_MODE is invalid");
	const pricingMode = get("--llm-pricing-mode", "E2E_LLM_PRICING_MODE");
	if (pricingMode !== "PRICED" && pricingMode !== "NO_CHARGE")
		throw new Error("E2E_LLM_PRICING_MODE must be PRICED or NO_CHARGE");
	const required = (value: string, message: string): string => {
		if (!value) throw new Error(message);
		return value;
	};
	const appUrl = get("--app-url", "E2E_APP_URL", "http://localhost:8080");
	const parsedApp = new URL(appUrl);
	if (
		parsedApp.protocol !== "http:" ||
		!["localhost", "127.0.0.1", "[::1]", "::1"].includes(parsedApp.hostname)
	)
		throw new Error("E2E_APP_URL must be a loopback URL");
	const databaseUrl = env.E2E_DB_URL ?? "postgresql://root:root@localhost:5432/hephaestus";
	const parsedDatabase = new URL(databaseUrl);
	if (
		!["postgres:", "postgresql:"].includes(parsedDatabase.protocol) ||
		!["localhost", "127.0.0.1", "[::1]", "::1"].includes(parsedDatabase.hostname)
	)
		throw new Error("E2E_DB_URL must be a loopback PostgreSQL URL");
	const number = (value: string, name: string): number => {
		if (!/^\d+(?:\.\d+)?$/.test(value)) throw new Error(`${name} must be a non-negative number`);
		return Number(value);
	};
	const input = get("--llm-input-usd", "E2E_LLM_INPUT_USD");
	const output = get("--llm-output-usd", "E2E_LLM_OUTPUT_USD");
	const repository = get("--repo", "E2E_REPO") || undefined;
	if (repository && !/^[A-Za-z0-9._/-]+$/.test(repository))
		throw new Error("E2E_REPO contains unsupported characters");
	const pullRequest = get("--pr-id", "E2E_PR_ID");
	if (pullRequest && !/^\d+$/.test(pullRequest)) throw new Error("E2E_PR_ID must be numeric");
	const priceNote = env.E2E_LLM_PRICE_NOTE;
	if (pricingMode === "NO_CHARGE" && !priceNote)
		throw new Error("E2E_LLM_PRICE_NOTE is required for NO_CHARGE");
	return {
		provider,
		pat: required(
			env[provider === "github" ? "E2E_GITHUB_PAT" : "E2E_GITLAB_PAT"] ?? "",
			"an SCM PAT is required through the environment",
		),
		serverUrl: get("--server-url", "E2E_SERVER_URL", "https://gitlab.lrz.de").replace(/\/$/, ""),
		llmKey: required(env.E2E_LLM_KEY ?? "", "E2E_LLM_KEY is required"),
		llmBaseUrl: required(
			get("--llm-base-url", "E2E_LLM_BASE_URL"),
			"E2E_LLM_BASE_URL is required",
		).replace(/\/$/, ""),
		model: required(get("--model", "E2E_MODEL"), "E2E_MODEL is required"),
		protocol,
		authMode,
		pricingMode,
		inputUsd: pricingMode === "PRICED" ? number(input, "E2E_LLM_INPUT_USD") : undefined,
		outputUsd: pricingMode === "PRICED" ? number(output, "E2E_LLM_OUTPUT_USD") : undefined,
		priceNote,
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
	if (!response.ok) throw new Error(`${context} failed with HTTP ${response.status}`);
	if (response.status === 204) return undefined;
	return parseJson(await response.text());
}

async function login(config: Config): Promise<string> {
	const response = await fetch(`${config.appUrl}/auth/dev-login`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ username: config.username, admin: true }),
		signal: AbortSignal.timeout(10_000),
	});
	if (!response.ok) throw new Error("dev-login failed");
	const cookie = response.headers.get("set-cookie");
	const jwt = /(?:__Host-)?HEPHAESTUS_AT=([^;]+)/.exec(cookie ?? "")?.[1];
	if (!jwt) throw new Error("dev-login returned no access token");
	return jwt;
}

async function main(): Promise<void> {
	const config = loadConfig(process.env, process.argv.slice(2));
	const llmHeaders: Record<string, string> =
		config.authMode === "API_KEY"
			? { "api-key": config.llmKey }
			: { authorization: `Bearer ${config.llmKey}` };
	const models = array(
		object(
			await jsonRequest(
				`${config.llmBaseUrl}/models`,
				{ headers: llmHeaders },
				"LLM model discovery",
			),
			"LLM model discovery",
		).data,
		"LLM model discovery",
	);
	if (!models.some((model) => model.id === config.model))
		throw new Error("the configured model is not listed by the LLM provider");
	let jwt = await login(config);
	const api = (
		method: string,
		path: string,
		body?: unknown,
		headers?: Record<string, string>,
	): Promise<unknown> =>
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
	// A signed-in person may not read anything else until they complete the current transparency
	// notice, so the seeding account completes it through the endpoint the interstitial uses.
	const consent = object(await api("GET", "/user/consent"), "consent status");
	if (consent.completed !== true)
		await api("PUT", "/user/consent", {
			noticeVersion: textField(consent, "noticeVersion", "consent status"),
			termsAccepted: true,
			participateInResearch: false,
		});
	const accountId = idField(object(await api("GET", "/user"), "user"), "user");
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
	if (!/^[A-Za-z0-9._-]+$/.test(scmLogin)) throw new Error("the SCM returned an unsupported login");
	if (config.repository) {
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
		if (resolved.toLowerCase() !== config.repository.toLowerCase())
			throw new Error("the SCM resolved a different target repository");
	}
	const database = new Client({
		connectionString: config.databaseUrl,
		connectionTimeoutMillis: 5000,
	});
	await database.connect();
	let selectedPullRequestId = config.pullRequestId;
	try {
		const account = await database.query<{ exists: boolean }>(
			"SELECT EXISTS (SELECT 1 FROM account WHERE id = $1) AS exists",
			[accountId],
		);
		if (!account.rows[0]?.exists)
			throw new Error("E2E_APP_URL and E2E_DB_URL point to different databases");
		await database.query("BEGIN");
		const provider = await database.query<{ id: number }>(
			"INSERT INTO identity_provider (type, server_url, created_at) VALUES ($1,$2,now()) ON CONFLICT (type, server_url) DO UPDATE SET server_url=EXCLUDED.server_url RETURNING id",
			[config.provider.toUpperCase(), scmOrigin],
		);
		const providerId = provider.rows[0]?.id;
		if (!providerId) throw new Error("identity provider seeding failed");
		const user = await database.query<{ id: number }>(
			"INSERT INTO \"user\" (native_id, provider_id, login, type, avatar_url, html_url, created_at, updated_at) VALUES ($1,$2,$3,'USER','',$4,now(),now()) ON CONFLICT (provider_id, native_id) DO UPDATE SET login=EXCLUDED.login RETURNING id",
			[scmId, providerId, scmLogin, `${scmOrigin}/${scmLogin}`],
		);
		const userId = user.rows[0]?.id;
		if (!userId) throw new Error("SCM user seeding failed");
		await database.query(
			"INSERT INTO identity_link (account_id, provider_id, subject, linked_at, linked_via, external_actor_id, username_at_signup) VALUES ($1,$2,$3,now(),'OAUTH_LOGIN',$4,$5) ON CONFLICT (account_id, provider_id, COALESCE(team_id, '')) WHERE disabled_at IS NULL DO NOTHING",
			[accountId, providerId, String(scmId), userId, scmLogin],
		);
		await database.query(
			"INSERT INTO account_feature (account_id, flag, enabled_at) VALUES ($1,'mentor_access',now()) ON CONFLICT DO NOTHING",
			[accountId],
		);
		await database.query("COMMIT");
		jwt = await login(config);
		const accountLogin = config.accountLogin ?? scmLogin;
		let workspace = array(await api("GET", "/workspaces"), "workspaces").find(
			(entry) => entry.workspaceSlug === config.workspaceSlug,
		);
		if (!workspace) {
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
		} else {
			if (
				typeof workspace.accountLogin !== "string" ||
				workspace.accountLogin.toLowerCase() !== accountLogin.toLowerCase() ||
				workspace.providerType !== config.provider.toUpperCase()
			)
				throw new Error("workspace slug belongs to a different SCM account or provider");
			if (config.provider === "gitlab") {
				const detail = object(await api("GET", `/workspaces/${config.workspaceSlug}`), "workspace");
				if (
					typeof detail.serverUrl !== "string" ||
					detail.serverUrl.replace(/\/$/, "") !== scmOrigin
				)
					throw new Error("workspace slug belongs to a different SCM server");
			}
		}
		if (!workspace) throw new Error("workspace create/lookup failed");
		const workspaceId = idField(workspace, "workspace");
		await api("PATCH", `/workspaces/${config.workspaceSlug}/token`, {
			personalAccessToken: config.pat,
		});
		await database.query(
			"INSERT INTO workspace_membership (workspace_id, user_id, role, league_points, hidden, created_at) VALUES ($1,$2,'ADMIN',0,false,now()) ON CONFLICT DO NOTHING",
			[workspaceId, userId],
		);
		await api("PATCH", `/workspaces/${config.workspaceSlug}/features`, {
			practicesEnabled: true,
			mentorEnabled: true,
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
		const settings = object(await api("GET", "/admin/llm/settings"), "LLM settings");
		if (settings.allowWorkspaceConnections !== true)
			await api("PUT", "/admin/llm/settings", { allowWorkspaceConnections: true });
		const connections = array(
			await api("GET", `/workspaces/${config.workspaceSlug}/llm/connections`),
			"LLM connections",
		);
		let connection = connections.find((entry) => entry.slug === "e2e-openai-compatible");
		if (
			connection &&
			(connection.baseUrl !== config.llmBaseUrl ||
				connection.apiProtocol !== config.protocol ||
				connection.authMode !== config.authMode)
		)
			throw new Error(
				"existing E2E connection uses a different immutable route; use a fresh workspace slug",
			);
		connection ??= object(
			await api("POST", `/workspaces/${config.workspaceSlug}/llm/connections`, {
				displayName: "E2E OpenAI-compatible",
				slug: "e2e-openai-compatible",
				baseUrl: config.llmBaseUrl,
				apiProtocol: config.protocol,
				authMode: config.authMode,
				apiKey: config.llmKey,
				enabled: false,
			}),
			"LLM connection",
		);
		const connectionId = idField(connection, "LLM connection");
		await api("PATCH", `/workspaces/${config.workspaceSlug}/llm/connections/${connectionId}`, {
			apiKey: config.llmKey,
		});
		const probe = object(
			await api(
				"POST",
				`/workspaces/${config.workspaceSlug}/llm/connections/${connectionId}/probe`,
			),
			"LLM probe",
		);
		if (probe.reachable !== true) throw new Error("OpenAI-compatible connection probe failed");
		const pricing =
			config.pricingMode === "PRICED"
				? {
						pricingMode: config.pricingMode,
						per1mInputUsd: config.inputUsd,
						per1mOutputUsd: config.outputUsd,
					}
				: { pricingMode: config.pricingMode, priceNote: config.priceNote };
		let catalogModel = array(
			await api("GET", `/workspaces/${config.workspaceSlug}/llm/models`),
			"LLM models",
		).find(
			(entry) => entry.connectionId === connectionId && entry.upstreamModelId === config.model,
		);
		catalogModel ??= object(
			await api(
				"POST",
				`/workspaces/${config.workspaceSlug}/llm/connections/${connectionId}/models`,
				{ displayName: "E2E model", upstreamModelId: config.model, enabled: false, ...pricing },
			),
			"LLM model",
		);
		const modelId = idField(catalogModel, "LLM model");
		await api("PATCH", `/workspaces/${config.workspaceSlug}/llm/connections/${connectionId}`, {
			enabled: true,
		});
		await api("PATCH", `/workspaces/${config.workspaceSlug}/llm/models/${modelId}`, {
			enabled: true,
			...pricing,
		});
		for (const purpose of ["PRACTICE_REVIEW", "MENTOR"])
			await api("PUT", `/workspaces/${config.workspaceSlug}/agents/${purpose}`, {
				workspaceModelId: modelId,
				enabled: true,
				timeoutSeconds: 1200,
				maxConcurrentJobs: 1,
				allowInternet: true,
			});
		const agents = array(
			await api("GET", `/workspaces/${config.workspaceSlug}/agents`),
			"agent bindings",
		);
		if (agents.filter((agent) => agent.ready === true).length !== 2)
			throw new Error("both agent purposes must be ready");
		const adoption = array(
			await api("GET", `/workspaces/${config.workspaceSlug}/practice-catalog/adoption`),
			"practice-catalog adoption",
		);
		const available = adoption.find((entry) => entry.availability === "AVAILABLE");
		if (available) {
			const slug = textField(available, "slug", "practice-catalog adoption");
			const preview = await fetch(
				`${config.appUrl}/workspaces/${config.workspaceSlug}/practice-catalog/adoption/${slug}`,
				{ headers: { authorization: `Bearer ${jwt}` }, signal: AbortSignal.timeout(30_000) },
			);
			if (!preview.ok)
				throw new Error(`practice adoption preview failed with HTTP ${preview.status}`);
			const etag = preview.headers.get("etag");
			const previewBody = object(parseJson(await preview.text()), "practice adoption preview");
			const definition = object(previewBody.definition, "practice adoption preview.definition");
			if (
				previewBody.slug !== slug ||
				previewBody.availability !== "AVAILABLE" ||
				previewBody.initialAutonomy !== "HUMAN_APPROVAL" ||
				textField(definition, "criteria", "practice adoption preview.definition").trim().length ===
					0 ||
				typeof previewBody.sourceReviewRuleFingerprint !== "string" ||
				previewBody.sourceReviewRuleFingerprint.length === 0 ||
				!etag
			)
				throw new Error("practice adoption preview is incomplete or inconsistent");
			const adopted = object(
				await jsonRequest(
					`${config.appUrl}/workspaces/${config.workspaceSlug}/practice-catalog/adoption/${slug}`,
					{ method: "POST", headers: { authorization: `Bearer ${jwt}`, "if-match": etag } },
					"practice adoption",
				),
				"practice adoption",
			);
			const autonomy = object(adopted.autonomy, "adopted practice autonomy");
			if (autonomy.effective !== "HUMAN_APPROVAL" || autonomy.override !== "HUMAN_APPROVAL")
				throw new Error("adopted practice did not start in human-approval mode");
		} else if (!adoption.some((entry) => entry.availability === "ADOPTED")) {
			throw new Error("practice catalog has neither an available nor an adopted practice");
		}
		const needs = [
			{ sourceKind: "scm.pull-request.core", stance: "REQUIRED" },
			{ sourceKind: "scm.pull-request.diff", stance: "REQUIRED" },
			{ sourceKind: "scm.pull-request.comments", stance: "REQUIRED" },
		];
		const definitions = [
			{
				slug: "submit-reviewable-work",
				name: "Submit reviewable work",
				signals: [
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
				signals: ["scm.pull_request.reviewed", "scm.pull_request.synchronized"],
				criteria:
					"After reviewers leave comments, the author addresses them with follow-up commits or replies rather than ignoring or force-resolving them.",
			},
			{
				slug: "plan-and-scope-issues",
				name: "Plan & scope issues",
				signals: ["scm.pull_request.opened"],
				criteria:
					"The MR references a well-defined, properly scoped issue and stays within that scope. Flag MRs with no linked issue or scope creep.",
			},
		];
		const existingPractices = array(
			await api("GET", `/workspaces/${config.workspaceSlug}/practices`),
			"practices",
		);
		for (const practice of existingPractices) {
			if (practice.autonomy && object(practice.autonomy, "practice autonomy").effective !== "OFF")
				await api(
					"PATCH",
					`/workspaces/${config.workspaceSlug}/practices/${textField(practice, "slug", "practice")}/autonomy`,
					{ autonomy: "OFF" },
				);
		}
		for (const definition of definitions) {
			const body = {
				name: definition.name,
				bindings: [{ signals: definition.signals, needs }],
				criteria: definition.criteria,
			};
			if (existingPractices.some((practice) => practice.slug === definition.slug))
				await api(
					"PATCH",
					`/workspaces/${config.workspaceSlug}/practices/${definition.slug}`,
					body,
				);
			else
				await api("POST", `/workspaces/${config.workspaceSlug}/practices`, {
					slug: definition.slug,
					...body,
				});
			await api(
				"PATCH",
				`/workspaces/${config.workspaceSlug}/practices/${definition.slug}/autonomy`,
				{ autonomy: "AUTOMATIC" },
			);
		}
		if (config.repository) {
			const monitored = await api("GET", `/workspaces/${config.workspaceSlug}/repositories`);
			if (!Array.isArray(monitored) || !monitored.includes(config.repository))
				await api(
					"POST",
					`/workspaces/${config.workspaceSlug}/repositories?nameWithOwner=${encodeURIComponent(config.repository)}`,
				);
		}
		const parameters: unknown[] = [workspaceId];
		let pullRequestScope =
			"FROM issue i JOIN repository r ON r.id=i.repository_id JOIN repository_to_monitor rtm ON rtm.workspace_id=$1 AND lower(rtm.name_with_owner)=lower(r.name_with_owner) AND (rtm.native_id IS NULL OR rtm.native_id=r.native_id) WHERE i.issue_type='PULL_REQUEST'";
		if (config.repository) {
			parameters.push(config.repository);
			pullRequestScope += ` AND lower(r.name_with_owner)=lower($${parameters.length})`;
		}
		if (selectedPullRequestId !== undefined) {
			parameters.push(selectedPullRequestId);
			const result = await database.query<{ exists: boolean }>(
				`SELECT EXISTS (SELECT 1 ${pullRequestScope} AND i.id=$${parameters.length}) AS exists`,
				parameters,
			);
			if (!result.rows[0]?.exists)
				throw new Error("E2E_PR_ID is not a pull request monitored by the target workspace");
		} else {
			const result = await database.query<{ id: number }>(
				`SELECT i.id ${pullRequestScope} ORDER BY i.updated_at DESC NULLS LAST, i.id DESC LIMIT 1`,
				parameters,
			);
			selectedPullRequestId = result.rows[0]?.id;
		}
		console.log(
			`✓ E2E ready. Workspace ${config.workspaceSlug} (id ${workspaceId}) is configured${selectedPullRequestId ? ` for PR ${selectedPullRequestId}` : ""}.`,
		);
	} catch (error) {
		await database.query("ROLLBACK").catch(() => undefined);
		throw error;
	} finally {
		await database.end();
	}
}

if (import.meta.main) await main();
