import { test as base, expect, type Page } from "@playwright/test";

import { E2E_BASE_URL } from "./urls.ts";

const SERVER_URL = process.env.E2E_SERVER_URL ?? "http://localhost:8080";

export const test = base.extend({
	context: async ({ context }, use) => {
		await context.route("**/env-config.js", (route) =>
			route.fulfill({
				contentType: "application/javascript",
				body: `window.__ENV__ = ${JSON.stringify({
					APPLICATION_SERVER_URL: SERVER_URL,
					APPLICATION_CLIENT_URL: E2E_BASE_URL,
					XSRF_COOKIE_NAME: "XSRF-TOKEN",
					TANSTACK_DEVTOOLS_ENABLED: "false",
					SENTRY_DSN: "",
				})};`,
			}),
		);
		await use(context);
	},
});

export { expect };

export async function loginAsDevAdmin(page: Page, username = "e2e"): Promise<void> {
	await page.goto("/login");
	const consent = page.getByRole("region", { name: "Your privacy" });
	if (await consent.isVisible()) {
		await consent.getByRole("button", { name: /^(Decline|Reject all)$/ }).click();
	}
	await page.getByPlaceholder("username").fill(username);
	await page.getByRole("button", { name: /continue as dev admin/i }).click();
	await page.waitForURL((url) => !url.pathname.startsWith("/login"));
	await page.goto("/consent");
	const terms = page.getByRole("checkbox", { name: /terms/i });
	await Promise.race([
		terms.waitFor({ state: "visible" }),
		page.waitForURL((url) => url.pathname !== "/consent"),
	]);
	if (await terms.isVisible()) {
		await terms.check();
		// The research question is only asked where an organisation is configured to run one.
		const decline = page.getByRole("radio", { name: /don't take part/ });
		if (await decline.isVisible()) await decline.click();
		await page.getByRole("button", { name: "Continue" }).click();
		// The consent route can mask its URL, so URL changes do not prove submission finished.
		await expect(page.getByRole("heading", { name: "Let's get you set up" })).toBeHidden();
		await page.waitForURL((url) => url.pathname !== "/consent");
	}
}
