/**
 * The journeys that make up most use, held to what they cost today (`journey-budgets.ts`). A change
 * that adds a read, a round trip or a moved landmark fails here and states the new cost, so it lands
 * as a reviewed edit of the budget, and a saving lowers the budget in the same change.
 */
import { expect, test } from "./fixtures.ts";
import { budgets } from "./journey-budgets.ts";
import { API, APP, journeyCost, WORKSPACE } from "./journey-cost.ts";

// The synthetic workspace's own content, which a page shows only once it has read its data.
const PROFILE = "Reviewers cannot see how to check your change";
const ASK = "Why did I get feedback about how to verify #223?";

// A read the page starts after it settles may still be waiting out its latency.
test.afterEach(async ({ context }) => context.unrouteAll({ behavior: "ignoreErrors" }));

test("a cold load of the practice profile", async ({ page }) => {
	const cost = await journeyCost(page, "MEMBER", async (journey, step) => {
		await journey.goto(`/w/${WORKSPACE}/practice-profile`);
		await step(PROFILE);
	});
	expect(cost).toStrictEqual(budgets.practiceProfile);
});

test("a cold load of Ask Hephaestus", async ({ page }) => {
	const cost = await journeyCost(page, "MEMBER", async (journey, step) => {
		await journey.goto(`/w/${WORKSPACE}/conversation`);
		await step(ASK);
	});
	expect(cost).toStrictEqual(budgets.askHephaestus);
});

test("a cold load of the feedback that waits for approval", async ({ page }) => {
	const cost = await journeyCost(page, "ADMIN", async (journey, step) => {
		await journey.goto(`/w/${WORKSPACE}/admin/practices/reviews/feedback`);
		await step("No feedback yet");
	});
	expect(cost).toStrictEqual(budgets.feedbackApproval);
});

test("from the practice profile to Ask Hephaestus", async ({ page }) => {
	const cost = await journeyCost(page, "MEMBER", async (journey, step) => {
		await journey.goto(`/w/${WORKSPACE}/practice-profile`);
		await step(PROFILE);
		await journey.getByRole("link", { name: "Ask Hephaestus" }).first().click();
		await expect(journey).toHaveURL(new RegExp(`/w/${WORKSPACE}/conversation`, "u"));
		await step(ASK, { byReader: true });
	});
	expect(cost).toStrictEqual(budgets.profileToAsk);
});

/**
 * The measure's own control: a page whose reads form a known chain, and which moves its content once
 * the last one answers. The rounds must count the chain exactly, and the shift must show.
 */
const CONTROL = `<!doctype html>
<html><body><main>
<div id="first" style="height: 100px; background: #ddd">The control page</div>
<p id="state"></p>
</main>
<script>
const read = (path) => fetch("${API}/control/" + path, { mode: "no-cors" });
(async () => {
	await read("a");
	await Promise.all([read("b"), read("c")]);
	// A read after a short timer, as after React's reveal delay, is one more round, not part of the last.
	await new Promise((resolve) => setTimeout(resolve, 300));
	await read("d");
	const inserted = document.createElement("div");
	inserted.style.height = "200px";
	document.querySelector("main").insertBefore(inserted, document.getElementById("first"));
	// Built from parts: the page's text, which the measure reads, also holds this script.
	document.getElementById("state").textContent = ["The control page", "read everything"].join(" ");
})();
</script></body></html>`;

test("the measure counts a known chain of reads and catches a layout shift", async ({ page }) => {
	const cost = await journeyCost(page, "MEMBER", async (journey, step) => {
		await journey.route(`${APP}/journey-control`, async (route) =>
			route.fulfill({ contentType: "text/html", body: CONTROL }),
		);
		await journey.goto("/journey-control");
		await step("The control page read everything");
	});
	expect(cost).toStrictEqual({
		reads: ["GET /control/a", "GET /control/b", "GET /control/c", "GET /control/d"],
		rounds: 3,
		moved: ["page"],
	});
});

/**
 * The control for a step the reader starts: a click shows the content, and a chain of six more reads
 * later moves it. The shifts before the content showed answer the click. The later one comes over
 * 500 ms after the click, since each read answers after at least 100 ms, so it must still show.
 */
const CLICK_CONTROL = `<!doctype html>
<html><body><main>
<button id="open" type="button">Open</button>
<div id="first" style="height: 100px; background: #ddd">The click control</div>
<p id="state"></p>
</main>
<script>
const read = (path) => fetch("${API}/control/" + path, { mode: "no-cors" });
document.getElementById("open").addEventListener("click", async () => {
	await read("e");
	// Built from parts: the page's text, which the measure reads, also holds this script.
	document.getElementById("state").textContent = ["The click control", "answered"].join(" ");
	for (const later of ["f1", "f2", "f3", "f4", "f5", "f6"]) {
		await read(later);
	}
	const inserted = document.createElement("div");
	inserted.style.height = "200px";
	document.querySelector("main").insertBefore(inserted, document.getElementById("first"));
});
</script></body></html>`;

test("the measure catches a shift that comes after a step the reader started has shown", async ({
	page,
}) => {
	const cost = await journeyCost(page, "MEMBER", async (journey, step) => {
		await journey.route(`${APP}/journey-click-control`, async (route) =>
			route.fulfill({ contentType: "text/html", body: CLICK_CONTROL }),
		);
		await journey.goto("/journey-click-control");
		await step("The click control");
		await journey.getByRole("button", { name: "Open" }).click();
		await step("The click control answered", { byReader: true });
	});
	expect(cost).toStrictEqual({
		reads: [
			"GET /control/e",
			"GET /control/f1",
			"GET /control/f2",
			"GET /control/f3",
			"GET /control/f4",
			"GET /control/f5",
			"GET /control/f6",
		],
		rounds: 1,
		moved: ["page"],
	});
});
