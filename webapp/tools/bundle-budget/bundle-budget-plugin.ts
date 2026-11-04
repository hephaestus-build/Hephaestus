import { readFile } from "node:fs/promises";
import path from "node:path";

import type { Plugin } from "vite";

import { BUNDLE_METADATA_FILE } from "../bundle-packages.ts";
import { bundleMetadata, entryViolations, measure } from "./bundle-budget.ts";

/**
 * Every page's cold load fetches the entry's static JavaScript before it renders. With the router
 * plugin's default groupings, only a route's `component`, `errorComponent` and `notFoundComponent`
 * are split: an import that `validateSearch`, `beforeLoad`, `loader` or `pendingComponent` uses stays
 * in the entry, with everything it imports. Growth against the base build is
 * `scripts/verify-webapp-build.ts`'s; this plugin holds what no build may do.
 */
const routeOnlyPackages = [
	"@dnd-kit/core",
	"@monaco-editor/react",
	"@tanstack/react-table",
	"@tanstack/table-core",
	"mermaid",
	"monaco-editor",
	"react-day-picker",
	"recharts",
	"shiki",
	"streamdown",
];

export function bundleBudget(): Plugin {
	let outDir = "";
	return {
		name: "bundle-budget",
		apply: "build",
		configResolved(config) {
			outDir = path.resolve(config.root, config.build.outDir);
		},
		// After every plugin's `writeBundle`, so the build metadata, written from the final bytes there, is on disk.
		closeBundle: {
			order: "post",
			async handler() {
				const metadata = bundleMetadata.parse(
					JSON.parse(await readFile(path.join(outDir, BUNDLE_METADATA_FILE), "utf8")),
				);
				const measured = measure(metadata.chunks);
				const violations = entryViolations(measured, routeOnlyPackages);
				if (violations.length > 0) {
					throw new Error(violations.join("\n"));
				}
				this.info(
					`entry: ${measured.sizes.entry} gzip bytes; ${Object.keys(measured.sizes.lazy).length} lazy chunks`,
				);
			},
		},
	};
}
