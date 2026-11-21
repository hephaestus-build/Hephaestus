import {
	test as base,
	type BrowserContext,
	expect,
	type Page,
	type TestInfo,
} from "@playwright/test";

import { E2E_BASE_URL } from "./urls.ts";

declare global {
	interface Window {
		reportCspViolation: (
			disposition: string,
			directive: string,
			blocked: string,
			source: string,
		) => void;
	}
}

export const test = base.extend<
	{ cspViolations: undefined },
	{ adminSession: Awaited<ReturnType<BrowserContext["storageState"]>> }
>({
	cspViolations: [
		async ({ context }, use, testInfo) => {
			const violations = await watchCspViolations(context, testInfo);
			await use(undefined);
			expect(violations(), "content security policy violations").toEqual([]);
		},
		{ auto: true },
	],
	// One sign-in for every test of a worker, for a spec that visits many routes as the same person.
	adminSession: [
		async ({ browser }, use) => {
			const context = await browser.newContext({ baseURL: E2E_BASE_URL });
			await loginAsDevAdmin(await context.newPage());
			await use(await context.storageState());
			await context.close();
		},
		{ scope: "worker" },
	],
});

export { expect } from "@playwright/test";

/**
 * Collects the page's content security policy violations in this context. The server's page policy allows only the
 * scripts that carry its nonce, so a test fails on any violation of it. A Trusted Types report, which the page only
 * reports for now, is noted on the test until Trusted Types can be enforced.
 */
export async function watchCspViolations(
	context: BrowserContext,
	testInfo: TestInfo,
): Promise<() => string[]> {
	const violations: string[] = [];
	await context.exposeFunction(
		"reportCspViolation",
		(disposition: string, directive: string, blocked: string, source: string) => {
			const violation = `${directive}: ${blocked} at ${source}`;
			if (disposition === "enforce") {
				violations.push(violation);
			} else if (!testInfo.annotations.some(({ description }) => description === violation)) {
				testInfo.annotations.push({ type: "csp-report", description: violation });
			}
		},
	);
	await context.addInitScript(() => {
		document.addEventListener("securitypolicyviolation", (event) => {
			window.reportCspViolation(
				event.disposition,
				event.effectiveDirective,
				event.blockedURI,
				`${event.sourceFile}:${event.lineNumber}:${event.columnNumber}`,
			);
		});
	});
	return () => violations;
}

export async function loginAsDevAdmin(page: Page, username = "e2e"): Promise<void> {
	await page.goto("/login");
	await page.getByPlaceholder("username").fill(username);
	await page.getByRole("button", { name: /continue as dev admin/iu }).click();
	await page.waitForURL((url) => !url.pathname.startsWith("/login"));
	await page.goto("/consent");
	const terms = page.getByRole("checkbox", { name: /terms/iu });
	await Promise.race([
		terms.waitFor({ state: "visible" }),
		page.waitForURL((url) => url.pathname !== "/consent"),
	]);
	if (await terms.isVisible()) {
		await terms.check();
		// The research question is only asked where an organisation is configured to run one.
		const decline = page.getByRole("radio", { name: /keep my data out of research/u });
		if (await decline.isVisible()) {
			await decline.click();
		}
		await page.getByRole("button", { name: "Save and continue" }).click();
		// The consent route can mask its URL, so URL changes do not prove submission finished.
		await expect(page.getByRole("heading", { name: "Get set up" })).toBeHidden();
		await page.waitForURL((url) => url.pathname !== "/consent");
	}
}
