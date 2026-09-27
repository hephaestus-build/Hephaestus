import { createRequire } from "node:module";
import path from "node:path";

import type { StorybookConfig } from "@storybook/react-vite";

const require = createRequire(import.meta.url);

function getAbsolutePath(value: string): string {
	return path.dirname(require.resolve(path.join(value, "package.json")));
}

/**
 * Titles start below `src/components`, as in the webapp: `context/ContextView`. The stories render
 * presentational components with fixtures and no worker, no Chrome API and no network.
 */
const config: StorybookConfig = {
	stories: [{ directory: "../src/components", files: "**/*.stories.@(ts|tsx)" }],
	addons: [
		getAbsolutePath("@storybook/addon-docs"),
		getAbsolutePath("@storybook/addon-a11y"),
		getAbsolutePath("@storybook/addon-vitest"),
		getAbsolutePath("@storybook/addon-themes"),
	],
	framework: { name: getAbsolutePath("@storybook/react-vite"), options: {} },
};

export default config;
