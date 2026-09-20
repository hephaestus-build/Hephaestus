import assert from "node:assert/strict";

import type { Response } from "@playwright/test";

import { expect, loginAsDevAdmin, test } from "./fixtures";

function isReviewSettingsWrite(response: Response) {
	return (
		response.url().includes("/practices/review-settings") && response.request().method() === "PATCH"
	);
}

test("practice review settings can be updated", async ({ page }) => {
	await loginAsDevAdmin(page);

	await page.goto("/w/e2e/admin/practices/review?section=when-and-where");
	await expect(page.getByRole("heading", { name: "Review", exact: true })).toBeVisible();
	await expect(page.getByRole("heading", { name: "Practice reviews" })).toBeVisible();
	await expect(page.getByText("How reviews start", { exact: true })).toBeVisible();

	const deliverAfterMerge = page.getByRole("switch", { name: "Post feedback after merge" });
	const before = await deliverAfterMerge.getAttribute("aria-checked");
	assert.ok(before !== null, "switch has no checked state");
	const [response] = await Promise.all([
		page.waitForResponse(isReviewSettingsWrite),
		deliverAfterMerge.click(),
	]);
	expect(response.status()).toBe(200);
	await expect(deliverAfterMerge).not.toHaveAttribute("aria-checked", before);
});
