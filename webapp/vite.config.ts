import path from "node:path";

import { tanstackRouter } from "@tanstack/router-plugin/vite";
import Terminal from "vite-plugin-terminal";
import { configDefaults, defineConfig } from "vite-plus";

import { entryChunkBudget } from "./tools/entry-chunk/entry-chunk-budget.ts";
import { loadLintConfig } from "./tools/oxlint/load-config.ts";
import { appSourcePlugins } from "./vite.shared.ts";

const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

const viteConfig = defineConfig({
	root: import.meta.dirname,
	lint,
	plugins: [
		tanstackRouter({ autoCodeSplitting: true }),
		...appSourcePlugins(),
		entryChunkBudget(),
		...Terminal({ output: ["terminal", "console"] }).map((plugin) =>
			plugin === false ? false : { ...plugin, apply: "serve" as const },
		),
	],
	build: {
		rolldownOptions: {
			// The generated zod schemas carry no pure annotations. Without this, a chunk that imports one schema ships all of them.
			// It marks every `z.*` call pure, so a call kept only for its side effect, such as `z.config(...)`, would be dropped.
			treeshake: { manualPureFunctions: ["z"] },
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
		port: Number.parseInt(process.env.WEBAPP_PORT ?? "", 10) || 4200,
		strictPort: true,
		// Storybook writes a separate site inside this root; rebuilding it must not reload the app.
		watch: { ignored: ["**/storybook-static/**"] },
		fs: {
			allow: [path.resolve(import.meta.dirname, "..")],
		},
	},
});

export default viteConfig;
