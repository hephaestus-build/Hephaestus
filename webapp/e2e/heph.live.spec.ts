import { type APIRequestContext, request as playwrightRequest } from "@playwright/test";

import {
	type Api,
	type E2eLlmRoute,
	provisionLlmModel,
	readLlmRoute,
} from "../../scripts/lib/e2e-llm.ts";
import { completeTransparencyNotice } from "../../scripts/lib/research-consent.ts";

import { expect, loginAsDevAdmin, test } from "./fixtures";

// Heph answering for real: a server with the worker role (the `e2e` profile, not `playwright`), Docker for the
// Heph host, and an OpenAI-compatible model. The model is read and provisioned by the same code as
// `scripts/e2e-setup.ts`, so the variables and defaults are the same. The key goes to the server in a
// request body and nowhere else.
const SERVER_URL = process.env.E2E_SERVER_URL ?? "http://localhost:8080";
const SEEDED_MENTOR_MODEL = 2;
const ANSWER_TIMEOUT = 240_000;

const configured = ["E2E_LLM_BASE_URL", "E2E_LLM_KEY", "E2E_MODEL"].every(
	(name) => (process.env[name] ?? "") !== "",
);

test.skip(
	!configured,
	"Set E2E_LLM_BASE_URL, E2E_LLM_KEY and E2E_MODEL to run Heph against a real model",
);
test.describe.configure({ mode: "serial", timeout: 2 * ANSWER_TIMEOUT });

async function adminApi(): Promise<APIRequestContext> {
	const signIn = await playwrightRequest.newContext({ baseURL: SERVER_URL });
	const login = await signIn.post("/auth/dev-login", { data: { username: "e2e", admin: true } });
	expect(login.ok()).toBe(true);
	const cookie = login.headers()["set-cookie"] ?? "";
	const token = /HEPHAESTUS_AT=(?<value>[^;]+)/u.exec(cookie)?.groups?.value ?? "";
	await signIn.dispose();
	expect(token, "dev login returns an access token").not.toBe("");
	return playwrightRequest.newContext({
		baseURL: SERVER_URL,
		extraHTTPHeaders: { authorization: `Bearer ${token}` },
	});
}

async function body(response: Awaited<ReturnType<APIRequestContext["get"]>>): Promise<unknown> {
	expect(response.ok(), `${response.url()} answered ${response.status()}`).toBe(true);
	const text = await response.text();
	return text === "" ? null : (JSON.parse(text) as unknown);
}

/** `context` as the shared provisioner calls the API. */
function apiOf(context: APIRequestContext): Api {
	return async (method, path, data) =>
		body(await context.fetch(path, { method, ...(data === undefined ? {} : { data }) }));
}

async function bindMentor(context: APIRequestContext, instanceModelId: number): Promise<void> {
	await body(
		await context.put("/workspaces/e2e/agents/MENTOR?dataHandlingTier=UNDECLARED", {
			data: { instanceModelId, enabled: true, timeoutSeconds: 300, maxConcurrentJobs: 4 },
		}),
	);
}

/** The instance model for this run, kept ready by the same provisioner as `scripts/e2e-setup.ts`. */
async function liveModel(context: APIRequestContext, route: E2eLlmRoute): Promise<number> {
	return provisionLlmModel(
		apiOf(context),
		"/admin/llm",
		{
			connection: { slug: "e2e-live-llm", displayName: "E2E live model" },
			modelDisplayName: route.model,
			pricing: { pricingMode: "NO_CHARGE", note: "E2E test model" },
		},
		route,
	);
}

let api: APIRequestContext;

test.beforeAll(async () => {
	api = await adminApi();
	// The server refuses an account every other request until it has completed the current notice.
	await completeTransparencyNotice(apiOf(api));
	const route = readLlmRoute(
		(_flag, name, fallback = "") => process.env[name] ?? fallback,
		process.env,
	);
	await bindMentor(api, await liveModel(api, route));
});

test.afterAll(async () => {
	await bindMentor(api, SEEDED_MENTOR_MODEL);
	await api.dispose();
});

test.beforeEach(async ({ page }) => {
	await loginAsDevAdmin(page);
	await page.goto("/w/e2e/mentor");
	await expect(page.getByRole("textbox", { name: "Message Heph" })).toBeVisible();
});

test("Heph answers a question and then a reply in its thread", async ({ page }) => {
	const conversation = page.getByRole("region", { name: "Conversation with Heph" });
	const answers = conversation.getByRole("button", { name: /^Helpful \(/u });
	// The seeded exchange has an answer: count only once the conversation has loaded.
	await expect(answers.first()).toBeAttached();
	const before = await answers.count();
	const question = `In one sentence, why do small pull requests help reviewers? ${crypto.randomUUID().slice(0, 8)}`;
	await page.getByRole("textbox", { name: "Message Heph" }).fill(question);
	await page.getByRole("button", { name: "Send message" }).click();
	await expect(answers).toHaveCount(before + 1, { timeout: ANSWER_TIMEOUT });
	await expect(page.getByRole("button", { name: "Stop Heph", exact: true })).toBeHidden();

	const asked = conversation.locator("[data-message-id]").filter({ hasText: question });
	await asked.hover();
	await asked.getByRole("button", { name: /^Reply in thread \(/u }).click();
	const panel = page.getByRole("complementary", { name: "Thread" });
	await panel.getByRole("textbox", { name: "Reply in thread" }).fill("Give me one example.");
	await panel.getByRole("button", { name: "Send message" }).click();
	await expect(panel.getByRole("button", { name: /^Helpful \(/u })).toHaveCount(1, {
		timeout: ANSWER_TIMEOUT,
	});
});

test("a developer stops Heph while it answers", async ({ page }) => {
	const question = `Write a 400-word essay about code review. ${crypto.randomUUID().slice(0, 8)}`;
	await page.getByRole("textbox", { name: "Message Heph" }).fill(question);
	await page.getByRole("button", { name: "Send message" }).click();
	const stop = page.getByRole("button", { name: "Stop Heph", exact: true });
	await expect(stop).toBeVisible();
	await stop.click();
	// The conversation outlives the test, so the state is read on this question alone.
	const asked = page
		.getByRole("region", { name: "Conversation with Heph" })
		.locator("[data-message-id]")
		.filter({ hasText: question });
	await expect(asked.getByText("Stopped", { exact: true })).toBeVisible({
		timeout: ANSWER_TIMEOUT,
	});
});
