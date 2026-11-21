/**
 * Moving around the app keeps the reader where they are: a link inside the app never loads the
 * document again, and a change of the search on the page the reader is on keeps their scroll. Like
 * `journeys.spec.ts`, it needs no server: the synthetic workspace answers the API.
 */
import { expect, test } from "./fixtures.ts";
import { openJourney, WORKSPACE } from "./journey-cost.ts";

const PROFILE = "Reviewers cannot see how to check your change";
const ASK = "Why did I get feedback about how to verify #223?";

test.afterEach(async ({ context }) => context.unrouteAll({ behavior: "ignoreErrors" }));

test("links inside the app load the document once", async ({ page }) => {
	const journey = await openJourney(page, "ADMIN");
	let documentLoads = 0;
	page.on("domcontentloaded", () => {
		documentLoads += 1;
	});
	await page.goto(`/w/${WORKSPACE}/practice-profile`);
	await journey.settle(PROFILE);

	const sidebar = page.locator('[data-slot="sidebar"]');
	await sidebar.getByRole("link", { name: "Ask Hephaestus" }).click();
	await journey.settle(ASK);
	await sidebar.getByRole("link", { name: "Practice profile" }).click();
	await journey.settle(PROFILE);
	await page.goBack();
	await journey.settle(ASK);
	await page.goForward();
	await journey.settle(PROFILE);

	expect(documentLoads).toBe(1);
});

test("a tab on the page keeps the reader's place", async ({ page }) => {
	// Short enough that the page scrolls while its tabs stay in view.
	await page.setViewportSize({ width: 1280, height: 360 });
	const journey = await openJourney(page, "ADMIN");
	await page.goto(`/w/${WORKSPACE}/admin/practices/review`);
	await journey.settle("Feedback mode");
	await page.evaluate(() => window.scrollTo(0, 80));
	await expect.poll(async () => page.evaluate(() => window.scrollY)).toBe(80);

	await page.getByRole("tab", { name: "Automated review" }).click();
	await expect(page).toHaveURL(/section=automated-review/u);
	await journey.settle("Automated review");

	// The router scrolls to the top on every commit unless the search change says otherwise.
	expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
});
