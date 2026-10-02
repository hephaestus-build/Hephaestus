import { defineConfig } from "@playwright/test";

const onCI = process.env.CI !== undefined;

/**
 * Runs the built `e2e` bundle (`wxt build --mode e2e`) in Chromium, in two projects so a result
 * says what it exercised: `providers` needs nothing but the bundle and fixture provider pages served
 * through `context.route`; `server` needs the real Hephaestus server under the `e2e` profile with
 * both seeds loaded, and fails before its first test when that server does not answer
 * (`extension/AGENTS.md` § End-to-end).
 */
export default defineConfig({
	testDir: "./e2e",
	fullyParallel: false,
	workers: 1,
	forbidOnly: onCI,
	retries: onCI ? 1 : 0,
	timeout: 60_000,
	expect: { timeout: 15_000 },
	reporter: onCI ? [["github"], ["list"]] : [["list"]],
	use: { trace: "retain-on-failure", screenshot: "only-on-failure" },
	projects: [
		{ name: "providers", testMatch: "providers.spec.ts" },
		{ name: "server", testMatch: "server.spec.ts" },
	],
});
