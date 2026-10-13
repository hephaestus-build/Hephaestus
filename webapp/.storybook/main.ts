import { createRequire } from "node:module";
import path from "node:path";

import type { StorybookConfig } from "@storybook/react-vite";

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
		const splitting = typeof output.codeSplitting === "object" ? output.codeSplitting : {};
		output.codeSplitting = {
			...splitting,
			groups: [
				...(splitting.groups ?? []),
				{ name: "storybook-stories", test: /storybook-stories\.js$/u },
			],
		};
		viteConfig.plugins ??= [];
		viteConfig.plugins.push(storybookChunkBudgets());
		viteConfig.optimizeDeps ??= {};
		viteConfig.optimizeDeps.include = [
			...(viteConfig.optimizeDeps.include ?? []),
			"@dnd-kit/core",
			"@dnd-kit/modifiers",
			"@dnd-kit/sortable",
			"@dnd-kit/utilities",
			"@sentry/react",
			"@tanstack/react-query-devtools",
			"@tanstack/react-router-devtools",
		];
		return viteConfig;
	},
};
export default config;
