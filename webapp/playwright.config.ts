import { defineConfig, devices } from "@playwright/test";

import { E2E_BASE_URL, E2E_PORT } from "./e2e/urls.ts";

const onCI = process.env.CI !== undefined;

export default defineConfig({
	testDir: "./e2e",
	testMatch: "**/*.spec.ts",
	fullyParallel: false,
	forbidOnly: onCI,
	workers: onCI ? 1 : undefined,
	// Retry for diagnostics, but fail CI on flaky tests.
	retries: onCI ? 1 : 0,
	failOnFlakyTests: onCI,
	timeout: 60_000,
	reporter: [
		...(onCI ? [["github"] as const] : [["list"] as const]),
		["./e2e/coverage-reporter.ts"],
	],
	use: {
		baseURL: E2E_BASE_URL,
		trace: "on-first-retry",
		screenshot: "only-on-failure",
	},
	projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
	webServer: {
		command: `vp build && vp preview --host 127.0.0.1 --port ${E2E_PORT}`,
		url: E2E_BASE_URL,
		reuseExistingServer: !onCI,
		timeout: 120_000,
	},
});
