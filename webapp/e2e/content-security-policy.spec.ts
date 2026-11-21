/**
 * The server's policy for the web app's page (ADR 0100, stage 3). Each load names its own script nonce, and every
 * script on the page carries it. Trusted Types are only reported: this spec proves that such a report reaches the
 * suite (`fixtures.ts`), so a run that lists none means a page that raised none.
 */
import type { Page, TestInfo } from "@playwright/test";

import { expect, test } from "./fixtures.ts";

const PAGE_NONCE = /script-src 'nonce-(?<nonce>[^']+)' 'strict-dynamic'/u;

/** The nonce that the page's policy names, and the nonce of each script on the page. */
async function loadNonces(page: Page) {
	const response = await page.goto("/login");
	const policy = (await response?.headerValue("content-security-policy")) ?? "";
	// The browser hides a nonce attribute's value once the page has loaded; the property keeps it. The config record is
	// JSON, not a script, and carries none.
	const scripts = await page
		.locator("script:not([type='application/json'])")
		.evaluateAll((elements) =>
			elements.map((element) => (element instanceof HTMLScriptElement ? element.nonce : "")),
		);
	return { policy: PAGE_NONCE.exec(policy)?.groups?.nonce ?? "", scripts };
}

const reports = (testInfo: TestInfo) =>
	testInfo.annotations
		.filter(({ type }) => type === "csp-report")
		.map(({ description }) => description ?? "");

test("each load of the page names a new script nonce, and every script on it carries that nonce", async ({
	page,
}) => {
	const first = await loadNonces(page);
	const second = await loadNonces(page);

	expect(first.policy).toMatch(/^[A-Za-z0-9+/]{22}==$/u);
	expect(first.scripts.length).toBeGreaterThan(0);
	expect(new Set(first.scripts)).toEqual(new Set([first.policy]));
	expect(new Set(second.scripts)).toEqual(new Set([second.policy]));
	expect(second.policy).not.toBe(first.policy);
});

test("a Trusted Types violation on the page reaches the suite as a report, not as an enforced violation", async ({
	page,
}, testInfo) => {
	await page.goto("/login");
	await page.evaluate(() => {
		document.createElement("div").innerHTML = "<b>a Trusted Types sink</b>";
	});

	await expect
		.poll(() => reports(testInfo))
		.toContainEqual(expect.stringContaining("require-trusted-types-for"));
});
