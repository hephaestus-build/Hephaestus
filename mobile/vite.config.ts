import { defineConfig } from "vite-plus";

import { loadLintConfig } from "../webapp/tools/oxlint/load-config.ts";

const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

// Vitest runs the app's pure modules only (session, contract, routing logic). Screens are proven on
// the simulators by the Maestro flows under `maestro/`, which is where React Native actually renders.
export default defineConfig({
	root: import.meta.dirname,
	lint,
	test: {
		include: ["src/**/*.test.ts"],
		reporters: ["default", "junit"],
		outputFile: { junit: "./test-results/junit-mobile.xml" },
	},
	resolve: { alias: { "@": new URL("src", import.meta.url).pathname } },
});
