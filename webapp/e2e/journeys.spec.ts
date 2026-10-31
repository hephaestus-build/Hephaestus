/**
 * The journeys that make up most use, held to what they cost today (`journey-budgets.ts`). A change
 * that adds a read, a round trip or a moved landmark fails here and states the new cost, so it lands
 * as a reviewed edit of the budget, and a saving lowers the budget in the same change.
 */
import { expect, test } from "@playwright/test";

import { budgets } from "./journey-budgets.ts";
import { openJourney, WORKSPACE } from "./journey-cost.ts";

// The synthetic workspace's own content, which a page shows only once it has read its data.
const PROFILE = "Reviewers cannot see how to check your change";
const ASK = "Why did I get feedback about how to verify #223?";

// A read the page starts after it settles may still be waiting out its latency.
test.afterEach(async ({ context }) => context.unrouteAll({ behavior: "ignoreErrors" }));

test("a cold load of the practice profile", async ({ page }) => {
	const journey = await openJourney(page, "MEMBER");
	await page.goto(`/w/${WORKSPACE}/practice-profile`);
	expect(await journey.settle(PROFILE)).toStrictEqual(budgets.practiceProfile);
});

test("a cold load of Ask Hephaestus", async ({ page }) => {
	const journey = await openJourney(page, "MEMBER");
	await page.goto(`/w/${WORKSPACE}/conversation`);
	expect(await journey.settle(ASK)).toStrictEqual(budgets.askHephaestus);
});

test("a cold load of the feedback that waits for approval", async ({ page }) => {
	const journey = await openJourney(page, "ADMIN");
	await page.goto(`/w/${WORKSPACE}/admin/practices/reviews/feedback`);
	expect(await journey.settle("No feedback yet")).toStrictEqual(budgets.feedbackApproval);
});

test("from the practice profile to Ask Hephaestus", async ({ page }) => {
	const journey = await openJourney(page, "MEMBER");
	await page.goto(`/w/${WORKSPACE}/practice-profile`);
	await journey.settle(PROFILE);
	await journey.reset();
	await page.getByRole("link", { name: "Ask Hephaestus" }).first().click();
	await expect(page).toHaveURL(new RegExp(`/w/${WORKSPACE}/conversation`, "u"));
	expect(await journey.settle(ASK)).toStrictEqual(budgets.profileToAsk);
});
