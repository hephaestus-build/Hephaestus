import path from "node:path";

import { storybookTest } from "@storybook/addon-vitest/vitest-plugin";
import { playwright } from "@vitest/browser-playwright";
import { defineConfig } from "vite-plus";

import { readJsonc } from "../webapp/tools/jsonc.ts";
import { loadLintConfig } from "../webapp/tools/oxlint/load-config.ts";
import { extensionAliases, extensionVitePlugins } from "./vite.shared.ts";

const formatConfig = readJsonc(new URL("../.oxfmtrc.json", import.meta.url));
const fmt = {
	...formatConfig,
	ignorePatterns: ["**/*.md", "**/*.html", "src/api/**", ".output/**", ".wxt/**"],
};
const lint = loadLintConfig(new URL(".oxlintrc.json", import.meta.url));

/**
 * `vp -C extension check` reads `fmt` and `lint`; `vp test --project unit|storybook` reads `test`.
 * The WXT build does not read this file — `wxt.config.ts` shares the plugins through
 * `vite.shared.ts` instead.
 */
export default defineConfig({
	root: import.meta.dirname,
	fmt,
	lint,
	resolve: { alias: extensionAliases },
	plugins: extensionVitePlugins(),
	test: {
		projects: [
			{
				extends: true,
				test: {
					name: "unit",
					include: ["src/**/*.test.ts"],
					environment: "node",
				},
			},
			{
				extends: true,
				plugins: [
					storybookTest({
						configDir: path.join(import.meta.dirname, ".storybook"),
						storybookScript: "vp run storybook:dev --ci",
					}),
				],
				optimizeDeps: {
					include: ["react", "react-dom", "react-dom/client", "lucide-react", "cn"],
				},
				test: {
					name: "storybook",
					browser: {
						enabled: true,
						headless: true,
						viewport: { width: 1024, height: 768 },
						provider: playwright({ contextOptions: { reducedMotion: "reduce" } }),
						instances: [{ browser: "chromium" }],
					},
					setupFiles: [".storybook/vitest.setup.ts"],
				},
			},
		],
	},
});
