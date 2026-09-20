import assert from "node:assert/strict";

import type { Response } from "@playwright/test";

import { expect, loginAsDevAdmin, test } from "./fixtures";

const LIVE_ENABLED = process.env.LIVE_INTEGRATION_E2E === "true";
const MUTATIONS_ENABLED = process.env.E2E_MUTATE_LIVE_INTEGRATIONS === "true";
const USERNAME = process.env.E2E_LIVE_USERNAME ?? "";
const GITHUB_WORKSPACE = process.env.E2E_GITHUB_WORKSPACE ?? "";
const GITLAB_WORKSPACE = process.env.E2E_GITLAB_WORKSPACE ?? "";
const LIVE_CONFIGURED = Boolean(USERNAME && GITHUB_WORKSPACE && GITLAB_WORKSPACE);
const LIVE_READY = LIVE_ENABLED && LIVE_CONFIGURED;

function isAcceptedSyncJob(response: Response) {
	return (
		response.request().method() === "POST" &&
		response.url().includes("/sync/jobs") &&
		[200, 202].includes(response.status())
	);
}

function acceptedJobId(job: unknown): number {
	const id = typeof job === "object" && job !== null && "id" in job ? job.id : undefined;
	assert.ok(
		typeof id === "number",
		`Expected the accepted sync job to carry a numeric id: ${JSON.stringify(job)}`,
	);
	return id;
}

test.describe("live integration operations", () => {
	test.skip(
		!LIVE_READY,
		"set LIVE_INTEGRATION_E2E, E2E_LIVE_USERNAME, E2E_GITHUB_WORKSPACE, and E2E_GITLAB_WORKSPACE",
	);

	test("GitHub catalog is workspace-specific and opens the live event stream", async ({ page }) => {
		await loginAsDevAdmin(page, USERNAME);
		const streamResponse = page.waitForResponse((response) =>
			response.url().endsWith(`/workspaces/${GITHUB_WORKSPACE}/sync/events`),
		);

		await page.goto(`/w/${GITHUB_WORKSPACE}/admin/integrations`);

		await expect(page.getByRole("heading", { name: "Integrations" })).toBeVisible();
		await expect(page.getByRole("heading", { name: "GitHub", exact: true })).toBeVisible();
		await expect(page.getByRole("heading", { name: "GitLab", exact: true })).toHaveCount(0);
		const stream = await streamResponse;
		expect(stream.status()).toBe(200);
		expect(stream.headers()["content-type"]).toContain("text/event-stream");
	});

	test("manual GitLab sync is accepted and reflected without a reload", async ({ page }) => {
		test.skip(
			!MUTATIONS_ENABLED,
			"set E2E_MUTATE_LIVE_INTEGRATIONS=true to run provider mutations",
		);
		await loginAsDevAdmin(page, USERNAME);
		await page.goto(`/w/${GITLAB_WORKSPACE}/admin/integrations/scm`);

		const accepted = page.waitForResponse(isAcceptedSyncJob);
		await page.getByRole("button", { name: "Sync now" }).click();

		const acceptedResponse = await accepted;
		expect([200, 202]).toContain(acceptedResponse.status());
		const jobId = acceptedJobId(await acceptedResponse.json());
		await expect(page.getByText(/sync started/iu)).toBeVisible();
		await expect(page.locator(`[data-job-id="${jobId}"]`)).toBeVisible({ timeout: 15_000 });
	});
});
