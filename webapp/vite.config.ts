import path from "node:path";

import { tanstackRouter } from "@tanstack/router-plugin/vite";
import type { Plugin } from "vite";
import Terminal from "vite-plugin-terminal";
import { configDefaults, defineConfig, loadEnv } from "vite-plus";

import { bundleBudget } from "./tools/bundle-budget/bundle-budget-plugin.ts";
import { BUNDLE_LICENSE_FILE, bundlePackages } from "./tools/bundle-packages.ts";
import { loadLintConfig } from "./tools/oxlint/load-config.ts";
import { appSourcePlugins } from "./vite.shared.ts";

const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

// The worktree's ports live in `server/.env` (docs/contributor/local-development.mdx § Port map). A variable in the
// shell wins. `loadEnv` takes name prefixes, and nothing it returns reaches the bundle: only the port and the
// runtime environment below read it.
const ports = loadEnv("development", path.resolve(import.meta.dirname, "../server"), [
	"WEBAPP_PORT",
	"SERVER_PORT",
]);
const webappPort = Number.parseInt(ports.WEBAPP_PORT ?? "", 10) || 4200;
const serverPort = Number.parseInt(ports.SERVER_PORT ?? "", 10) || 8080;

/**
 * Serves the dev server's runtime environment, as the container entrypoint writes it in production, so the app
 * calls this worktree's server and not the default port.
 */
const devRuntimeEnvironment = (): Plugin => ({
	name: "hephaestus:dev-runtime-environment",
	apply: "serve",
	configureServer(server) {
		server.middlewares.use("/env-config.js", (_request, response) => {
			response.setHeader("Content-Type", "text/javascript");
			response.end(
				`window.__ENV__ = ${JSON.stringify({
					APPLICATION_CLIENT_URL: `http://localhost:${webappPort}`,
					APPLICATION_SERVER_URL: `http://localhost:${serverPort}`,
				})};`,
			);
		});
	},
});

const viteConfig = defineConfig({
	root: import.meta.dirname,
	lint,
	plugins: [
		tanstackRouter({ autoCodeSplitting: true }),
		...appSourcePlugins(),
		bundlePackages(),
		bundleBudget(),
		devRuntimeEnvironment(),
		...Terminal({ output: ["terminal", "console"] }).map((plugin) =>
			plugin === false ? false : { ...plugin, apply: "serve" as const },
		),
	],
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
		projects: [
			{
				extends: true,
				test: {
					name: "app",
					environment: "jsdom",
					exclude: [...configDefaults.exclude, "e2e/**/*.spec.ts", "tools/oxlint/rules/**"],
					setupFiles: ["./src/test/setup-msw.ts"],
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
		// Storybook writes a separate site inside this root; rebuilding it must not reload the app.
		watch: { ignored: ["**/storybook-static/**"] },
		fs: {
			allow: [path.resolve(import.meta.dirname, "..")],
		},
	},
});

export default viteConfig;
