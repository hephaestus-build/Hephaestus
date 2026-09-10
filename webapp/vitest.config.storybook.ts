import path from "node:path";

import { storybookTest } from "@storybook/addon-vitest/vitest-plugin";
import { playwright } from "@vitest/browser-playwright";
import { defineConfig } from "vitest/config";

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
		alias: [
			{
				find: "@monaco-editor/react",
				replacement: path.resolve(import.meta.dirname, "./src/test/monaco-editor-react.mock.tsx"),
			},
			{ find: "@", replacement: path.resolve(import.meta.dirname, "./src") },
		],
	},
	optimizeDeps: {
		noDiscovery: true,
		include: [
			...runtimeDeps.filter((dependency) => dependency !== "@monaco-editor/react"),
			"use-sync-external-store/shim",
			"use-sync-external-store/shim/with-selector",
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
