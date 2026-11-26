import { defineConfig, devices } from "@playwright/test";

import { E2E_BASE_URL } from "./e2e/urls.ts";

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
		// The failed attempt is the one to read: a retry can pass, and a hang begins in the first attempt.
		trace: "retain-on-first-failure",
		screenshot: "only-on-failure",
	},
	projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
