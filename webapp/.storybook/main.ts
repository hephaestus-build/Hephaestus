import { createRequire } from "node:module";
import path from "node:path";

import type { StorybookConfig } from "@storybook/react-vite";
import { mergeConfig } from "vite";

import { storybookChunkBudgets } from "./chunk-budgets.ts";

const require = createRequire(import.meta.url);

function getAbsolutePath(value: string): string {
	return path.dirname(require.resolve(path.join(value, "package.json")));
}

const config: StorybookConfig = {
	// A title starts at the segment that says something: `admin/workspace-llm/ModelPicker`, not
	// `components/admin/workspace-llm/ModelPicker`; the runtime's few stories keep their `runtime` root.
	stories: [
		{ directory: "../src/components", files: "**/*.stories.@(ts|tsx)" },
		{ directory: "../src/runtime", titlePrefix: "runtime", files: "**/*.stories.@(ts|tsx)" },
	],
	addons: [
		getAbsolutePath("@storybook/addon-docs"),
		getAbsolutePath("@storybook/addon-onboarding"),
		getAbsolutePath("@storybook/addon-a11y"),
		getAbsolutePath("@chromatic-com/storybook"),
		getAbsolutePath("@storybook/addon-vitest"),
		getAbsolutePath("@storybook/addon-themes"),
	],
	framework: {
		name: getAbsolutePath("@storybook/react-vite"),
		options: {},
	},
	// The MSW worker, for stories and story tests only: outside Vite's `public/`, the app build never
	// ships it.
	staticDirs: ["./public"],
	// Pre-bundling prevents Vite from reloading the page while browser-mode tests are running.
	viteFinal: (viteConfig) => {
		viteConfig.build ??= {};
		viteConfig.build.rolldownOptions ??= {};
		// The budget plugin checks every chunk, failing unknown oversize or growth in the two
		// monolithic tool assets. Replace only Vite's redundant aggregate warning, not other logs.
		viteConfig.build.rolldownOptions.onLog = (level, log, handler) => {
			if (
				log.plugin === "builtin:vite-reporter" &&
				log.message.startsWith("\n(!) Some chunks are larger than 500 kB after minification.")
			) {
				return;
			}
			handler(level, log);
		};
		// The story index lists every story with its preload dependencies, so it grows with each story.
		// It is application code: its own chunk keeps it out of the preview runtime's tooling budget.
		viteConfig.build.rolldownOptions.output ??= {};
		const { output } = viteConfig.build.rolldownOptions;
		if (Array.isArray(output)) {
			throw new TypeError("Storybook builds one Rolldown output");
		}
		// The upstream UI's shared dependencies stay in separate chunks, so modules must still run in source order.
		output.strictExecutionOrder = true;
		const splitting = typeof output.codeSplitting === "object" ? output.codeSplitting : {};
		output.codeSplitting = {
			...splitting,
			groups: [
				...(splitting.groups ?? []),
				{ name: "storybook-stories", test: /storybook-stories\.js$/u },
				// Preserve the upstream UI module boundary without recursively merging its shared dependencies.
				{
					name: "storybook-components",
					test: /\/node_modules\/storybook\/dist\/components\/index\.js$/u,
					includeDependenciesRecursively: false,
				},
			],
		};
		// The app entry budget and release inventory describe the SPA. The preview has its own chunk budgets.
		viteConfig.plugins = (viteConfig.plugins ?? []).filter(
			(plugin) =>
				!(
					typeof plugin === "object" &&
					plugin !== null &&
					"name" in plugin &&
					(plugin.name === "entry-chunk-budget" || plugin.name === "bundle-packages")
				),
		);
		viteConfig.plugins.push(storybookChunkBudgets());
		viteConfig.optimizeDeps ??= {};
		viteConfig.optimizeDeps.include = [
			...(viteConfig.optimizeDeps.include ?? []),
			"@dnd-kit/core",
			"@dnd-kit/modifiers",
			"@dnd-kit/sortable",
			"@dnd-kit/utilities",
			"@tanstack/react-query-devtools",
			"@tanstack/react-router-devtools",
		];
		// Stories render the form contract; the production runtime check exercises Monaco itself.
		return mergeConfig(viteConfig, {
			resolve: {
				alias: [
					{
						find: /^@\/components\/common\/CodeEditor$/u,
						replacement: path.resolve(import.meta.dirname, "../src/test/code-editor.mock.tsx"),
					},
				],
			},
		});
	},
};
export default config;
