import { createRequire } from "node:module";
import { dirname, join } from "node:path";

import type { StorybookConfig } from "@storybook/react-vite";

const require = createRequire(import.meta.url);

function getAbsolutePath(value: string): string {
	return dirname(require.resolve(join(value, "package.json")));
}

const config: StorybookConfig = {
	stories: ["../src/**/*.stories.@(js|jsx|mjs|ts|tsx)"],
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
		viteConfig.optimizeDeps ??= {};
		viteConfig.optimizeDeps.include = [
			...(viteConfig.optimizeDeps.include ?? []),
			"@ai-sdk/react",
			"@dnd-kit/core",
			"@dnd-kit/modifiers",
			"@dnd-kit/sortable",
			"@dnd-kit/utilities",
			"@sentry/react",
			"@tanstack/react-query-devtools",
			"@tanstack/react-router-devtools",
			"ai",
			"uuid",
		];
		return viteConfig;
	},
};
export default config;
