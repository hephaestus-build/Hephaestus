import { gzipSync } from "node:zlib";

import type { Plugin } from "vite";

// These are monolithic upstream tools, not code shipped to application users. Keep narrow
// budgets so a dependency update must explain growth; everything else retains Vite's 500kB limit.
const TOOL_BUDGETS = [
	{ module: "/node_modules/storybook/dist/preview/runtime.js", bytes: 1_350_000, gzip: 375_000 },
	{ module: "/node_modules/axe-core/axe.js", bytes: 600_000, gzip: 165_000 },
];

export function checkChunkBudget(code: string, moduleIds: string[]) {
	const bytes = Buffer.byteLength(code);
	const budget = TOOL_BUDGETS.find(({ module }) =>
		moduleIds.some((id) => id.replaceAll("\\", "/").endsWith(module)),
	);
	if (!budget) {
		if (bytes <= 500_000) return;
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
				if (output.type !== "chunk") continue;
				const report = checkChunkBudget(output.code, Object.keys(output.modules));
				if (report) this.info(report);
			}
		},
	};
}
