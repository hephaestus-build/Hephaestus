import path from "node:path";

import { storybookTest } from "@storybook/addon-vitest/vitest-plugin";
import { defineConfig } from "vite-plus";
import { playwright } from "vite-plus/test/browser-playwright";

import pkg from "./package.json" with { type: "json" };
import { appSourcePlugins } from "./vite.shared.ts";

const runtimeDeps = Object.keys(pkg.dependencies);

export default defineConfig({
	plugins: [
		...appSourcePlugins(),
		storybookTest({
			configDir: path.join(import.meta.dirname, ".storybook"),
			storybookScript: "vp run storybook:dev --ci",
		}),
	],
	resolve: {
		tsconfigPaths: true,
	},
	optimizeDeps: {
		noDiscovery: true,
		include: [
			// `@shadcn/react` has no root export; only the subpath the app imports can be pre-bundled.
			...runtimeDeps.filter(
				(dependency) => dependency !== "monaco-editor" && dependency !== "@shadcn/react",
			),
			"@shadcn/react/questionnaire",
			// Base UI, TanStack Store and Recharts import React's shim; it is not an app dependency.
			"@base-ui/react > use-sync-external-store/shim",
			"@base-ui/react > use-sync-external-store/shim/with-selector",
		],
	},
	test: {
		name: "storybook",
		maxWorkers: 2,
		isolate: false,
		browser: {
			enabled: true,
			headless: true,
			// Stories read the real window through useIsMobile(), so the width decides which branch
			// they render. Matches the Chromatic viewport in .storybook/preview.tsx, so a play
			// function and its snapshot are asserting about the same layout.
			viewport: { width: 1440, height: 900 },
			provider: playwright({
				contextOptions: { reducedMotion: "reduce" },
			}),
			instances: [{ browser: "chromium" }],
		},
		setupFiles: [".storybook/vitest.setup.ts"],
		reporters: ["default", "junit"],
		outputFile: {
			junit: "./test-results/junit-storybook.xml",
		},
	},
});
