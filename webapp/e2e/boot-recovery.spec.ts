/**
 * A script that the app needs to start can fail to load, for example when the network changes during
 * the load. `public/boot-recovery.js` reloads once and then says that the page could not load,
 * instead of leaving it blank.
 */
import type { Request } from "@playwright/test";

import { expect, test } from "./fixtures";

// React's own chunk: every page needs it before anything renders.
const STARTUP_CHUNK = "**/assets/react-runtime-*.js";

test("a page whose startup scripts do not load reloads once, then says so and reloads on request", async ({
	page,
	context,
}) => {
	await context.route(STARTUP_CHUNK, async (route) => route.abort("internetdisconnected"));
	// The reload starts before the first document's `load` event, so the test counts document requests.
	const requests: Request[] = [];
	page.on("request", (request) => requests.push(request));

	await page.goto("/login");

	await expect(page.getByRole("heading", { name: "We could not load this page" })).toBeVisible();
	await expect(page.getByText("Check your connection, then reload the page.")).toBeVisible();
	const navigatedFrames = requests
		.filter((request) => request.isNavigationRequest())
		.map((request) => request.frame());
	expect(navigatedFrames).toEqual([page.mainFrame(), page.mainFrame()]);

	await context.unroute(STARTUP_CHUNK);
	await page.getByRole("button", { name: "Reload" }).click();

	await expect(page.getByPlaceholder("username")).toBeVisible();
	await expect(page.getByRole("heading", { name: "We could not load this page" })).toBeHidden();
});
