import { defineConfig, devices } from "@playwright/test";

import { BASE_URL, PORT } from "./e2e/screenshots/server.ts";

// The user-guide screenshots: `docs/contributor/documentation-guide.md` § Screenshots of the web app.
export default defineConfig({
	testDir: "./e2e/screenshots",
	testMatch: "**/*.shot.ts",
	fullyParallel: true,
	reporter: [["list"]],
	use: { baseURL: BASE_URL },
	projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
	webServer: {
		command: `vp build && vp preview --host 127.0.0.1 --port ${PORT} --strictPort`,
		url: BASE_URL,
		reuseExistingServer: false,
		timeout: 120_000,
	},
});
