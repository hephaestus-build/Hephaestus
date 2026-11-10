import { gzipSync } from "node:zlib";

import type { Plugin } from "vite";

// These are monolithic upstream tools in the Storybook preview only, never code shipped to
// application users. Keep narrow budgets so growth must explain itself; everything else retains
// Vite's 500kB limit. Rolldown groups modules by the stories that import them: once component
// stories stopped importing MSW and TanStack Query, the shared libraries that preview decorators
// also use joined the runtime chunk, and Storybook's docs internals formed a chunk of their own.
const TOOL_BUDGETS = [
	{ module: "/node_modules/storybook/dist/preview/runtime.js", bytes: 1_300_000, gzip: 372_000 },
	{ module: "/node_modules/axe-core/axe.js", bytes: 672_000, gzip: 190_000 },
	{ module: "/node_modules/@storybook/addon-docs/dist/blocks.js", bytes: 536_000, gzip: 175_000 },
];

export function checkChunkBudget(code: string, moduleIds: string[]) {
	const bytes = Buffer.byteLength(code);
	const budget = TOOL_BUDGETS.find(({ module }) =>
		moduleIds.some((id) => id.replaceAll("\\", "/").endsWith(module)),
	);
	if (!budget) {
		if (bytes <= 500_000) {
			return;
		}
		throw new Error(`Unrecognized oversized Storybook chunk: ${bytes} bytes (limit 500000)`);
	}
	const gzip = gzipSync(code).byteLength;
	if (bytes > budget.bytes || gzip > budget.gzip) {
		throw new Error(
			`${budget.module} exceeds its tooling budget: ${bytes}/${budget.bytes} bytes, ${gzip}/${budget.gzip} gzip bytes`,
		);
	}
	return `${budget.module}: ${bytes}/${budget.bytes} bytes, ${gzip}/${budget.gzip} gzip bytes`;
}

export function storybookChunkBudgets(): Plugin {
	return {
		name: "storybook-chunk-budgets",
		apply: "build",
		// Vite expands its preload map after generateBundle; budget the final bytes written.
		writeBundle(_options, bundle) {
			for (const output of Object.values(bundle)) {
				if (output.type !== "chunk") {
					continue;
				}
				const report = checkChunkBudget(output.code, Object.keys(output.modules));
				if (report !== undefined) {
					this.info(report);
				}
			}
		},
	};
}
