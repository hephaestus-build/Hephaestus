import path from "node:path";

import { sentryVitePlugin } from "@sentry/vite-plugin";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import type { OxfmtConfig } from "oxfmt";
import Terminal from "vite-plugin-terminal";
import { configDefaults } from "vitest/config";

import { readJsonc } from "./tools/jsonc.ts";
import { loadLintConfig } from "./tools/oxlint/load-config.ts";
import { appSourcePlugins } from "./vite.shared.ts";

const formatConfig = readJsonc<OxfmtConfig>(new URL("../.oxfmtrc.json", import.meta.url));
const fmt = {
	...formatConfig,
	ignorePatterns: [
		"**/*.md",
		"**/*.html",
		"src/api/**",
		"src/routeTree.gen.ts",
		"public/mockServiceWorker.js",
	],
};

const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

const sentryUploadValues = [
	process.env.SENTRY_AUTH_TOKEN,
	process.env.SENTRY_ORG,
	process.env.SENTRY_PROJECT,
];
const sentryUploadConfigured = sentryUploadValues.every(Boolean);
if (sentryUploadValues.some(Boolean) && !sentryUploadConfigured) {
	throw new Error(
		"Sentry source-map upload requires SENTRY_AUTH_TOKEN, SENTRY_ORG, and SENTRY_PROJECT",
	);
}
const viteConfig = {
	root: import.meta.dirname,
	fmt,
	lint,
	plugins: [
		tanstackRouter({ autoCodeSplitting: true }),
		...appSourcePlugins(),
		sentryVitePlugin({
			org: process.env.SENTRY_ORG,
			project: process.env.SENTRY_PROJECT,
			authToken: process.env.SENTRY_AUTH_TOKEN,
			disable: !sentryUploadConfigured,
			telemetry: false,
		}),
		...Terminal({ output: ["terminal", "console"] }).map((plugin) =>
			plugin === false ? false : { ...plugin, apply: "serve" as const },
		),
	],
	build: {
		sourcemap: "hidden" as const,
		rolldownOptions: {
			output: {
				codeSplitting: {
					// Keep the shared renderer cacheable across application releases. Do not collect all
					// dependencies: feature libraries belong to the routes that actually use them.
					groups: [
						{ name: "react-runtime", test: /[/]node_modules[/](?:react|react-dom|scheduler)[/]/u },
					],
				},
			},
		},
	},
	test: {
		globals: true,
		environment: "jsdom",
		exclude: [...configDefaults.exclude, "e2e/**/*.spec.ts"],
		setupFiles: ["./src/test/setup-msw.ts"],
		reporters: ["default", "junit"],
		outputFile: {
			junit: "./test-results/junit-webapp.xml",
		},
	},
	resolve: {
		alias: {
			"@": path.resolve(import.meta.dirname, "./src"),
		},
	},
	server: {
		port: Number.parseInt(process.env.WEBAPP_PORT ?? "", 10) || 4200,
		strictPort: true,
		// Storybook writes a separate site inside this root; rebuilding it must not reload the app.
		watch: { ignored: ["**/storybook-static/**"] },
		fs: {
			allow: [path.resolve(import.meta.dirname, "..")],
		},
	},
};

export default viteConfig;
