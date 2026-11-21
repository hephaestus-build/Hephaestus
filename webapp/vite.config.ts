import path from "node:path";

import { tanstackRouter } from "@tanstack/router-plugin/vite";
import Terminal from "vite-plugin-terminal";
import { configDefaults, defineConfig, loadEnv } from "vite-plus";

import { bundleBudget } from "./tools/bundle-budget/bundle-budget-plugin.ts";
import { BUNDLE_LICENSE_FILE, bundlePackages } from "./tools/bundle-packages.ts";
import { loadLintConfig } from "./tools/oxlint/load-config.ts";
import { precompress } from "./tools/precompress.ts";
import { appSourcePlugins } from "./vite.shared.ts";

const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

// The worktree's ports live in `server/.env` (docs/contributor/local-development.mdx § Port map). A variable in the
// shell wins. `loadEnv` takes name prefixes, and nothing it returns reaches the bundle: only the ports below read it.
const ports = loadEnv("development", path.resolve(import.meta.dirname, "../server"), [
	"WEBAPP_PORT",
	"SERVER_PORT",
]);
const webappPort = Number.parseInt(ports.WEBAPP_PORT ?? "", 10) || 4200;
const serverPort = Number.parseInt(ports.SERVER_PORT ?? "", 10) || 8080;

const viteConfig = defineConfig({
	root: import.meta.dirname,
	lint,
	plugins: [
		tanstackRouter({ autoCodeSplitting: true }),
		...appSourcePlugins(),
		bundlePackages(),
		precompress(),
		bundleBudget(),
		...Terminal({ output: ["terminal", "console"] }).map((plugin) =>
			plugin === false ? false : { ...plugin, apply: "serve" as const },
		),
	],
	// The server writes each request's script nonce in place of this placeholder (`WebAppIndex`).
	html: { cspNonce: "HEPHAESTUS_CSP_NONCE" },
	build: {
		license: { fileName: BUNDLE_LICENSE_FILE },
		rolldownOptions: {
			// The generated zod schemas carry no pure annotations. Without this, a chunk that imports one schema ships all of them.
			// It marks every `z.*` call pure, so a call kept only for its side effect, such as `z.config(...)`, would be dropped.
			// The generated client has no side effects. An import through `sdk.gen` or `react-query.gen`
			// then reaches only the operation that it names (scripts/api-generator/webapp.config.ts).
			treeshake: {
				manualPureFunctions: ["z"],
				moduleSideEffects: [{ test: /[/]webapp[/]src[/]api[/]/u, sideEffects: false }],
			},
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
		reporters: ["default", "junit"],
		outputFile: {
			junit: "./test-results/junit-webapp.xml",
		},
		// jsdom lays nothing out, so every chart measures 0 by 0 and Recharts warns on each render. A
		// measured size would only make each chart draw its SVG in every test.
		onConsoleLog: (log: string) => !log.includes("of chart should be greater than 0"),
		// `test:webapp` measures it (docs/contributor/testing.mdx § Web app coverage). A source file that
		// no test loads counts as uncovered.
		coverage: {
			include: ["src/**/*.{ts,tsx}"],
			exclude: [
				"src/api/**",
				"src/routeTree.gen.ts",
				"src/**/*.d.ts",
				"src/**/*.test.{ts,tsx}",
				"src/**/*.stories.tsx",
				"src/**/*fixtures.ts",
				"src/**/__mocks__/**",
				"src/mocks/**",
				"src/routes/**/-*-server.ts",
				"src/runtime/feature-flags/testing.ts",
				"src/components/layout/sidebar/sidebar-story-frame.tsx",
				"src/stories/**",
				"src/test/**",
			],
			reporter: ["text-summary", "json-summary"],
			reportsDirectory: "./test-results/coverage",
			thresholds: { statements: 90, branches: 78, functions: 83, lines: 90 },
		},
		projects: [
			{
				extends: true,
				test: {
					name: "app",
					environment: "jsdom",
					exclude: [...configDefaults.exclude, "e2e/**/*.spec.ts", "tools/oxlint/rules/**"],
					setupFiles: ["./src/test/setup-zod.ts", "./src/test/setup-msw.ts"],
				},
			},
			{
				extends: true,
				test: {
					name: "lint-rules",
					environment: "node",
					include: ["tools/oxlint/rules/**/*.test.ts"],
				},
			},
		],
	},
	resolve: {
		tsconfigPaths: true,
	},
	server: {
		port: webappPort,
		strictPort: true,
		// The app and its API share one origin, as they do in production.
		proxy: { "/api": `http://localhost:${serverPort}` },
		// Storybook writes a separate site inside this root; rebuilding it must not reload the app.
		watch: { ignored: ["**/storybook-static/**"] },
		fs: {
			allow: [path.resolve(import.meta.dirname, "..")],
		},
	},
});

export default viteConfig;
