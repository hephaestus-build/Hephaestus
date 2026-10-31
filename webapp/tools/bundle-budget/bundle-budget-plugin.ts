import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import type { Plugin } from "vite";

import { BUNDLE_METADATA_FILE } from "../bundle-packages.ts";
import {
	checkBudget,
	bundleMetadata,
	bundleSizes,
	measure,
	TOLERANCE_BYTES,
} from "./bundle-budget.ts";

/**
 * Every page's cold load fetches the entry's static JavaScript before it renders, and a lazy chunk
 * fetches what it adds when its route or component first shows. With the router plugin's default
 * groupings, only a route's `component`, `errorComponent` and `notFoundComponent` are split: an
 * import that `validateSearch`, `beforeLoad`, `loader` or `pendingComponent` uses stays in the entry,
 * with everything it imports.
 */
const BASELINE = new URL("baseline.json", import.meta.url);

const BASELINE_PATH = "webapp/tools/bundle-budget/baseline.json";

const UPDATE_COMMAND = "BUNDLE_BUDGET=update vp run build:webapp";

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
				if (process.env.BUNDLE_BUDGET === "update") {
					await writeFile(BASELINE, `${JSON.stringify(measured.sizes, null, "\t")}\n`);
					this.info(`bundle budget: wrote ${BASELINE.pathname}`);
					return;
				}
				const baseline = bundleSizes.parse(JSON.parse(await readFile(BASELINE, "utf8")));
				const { violations, lowerable } = checkBudget(measured, baseline, routeOnlyPackages);
				if (violations.length > 0) {
					throw new Error(
						[
							...violations,
							`Growth of up to ${TOLERANCE_BYTES} bytes keeps the baseline. When the growth is intended, run \`${UPDATE_COMMAND}\` and commit ${BASELINE_PATH}.`,
						].join("\n"),
					);
				}
				if (lowerable.length > 0) {
					this.info(
						[
							`bundle budget: the baseline can be lowered. \`${UPDATE_COMMAND}\` takes the saving, also in passing in the next pull request that touches ${BASELINE_PATH}.`,
							...lowerable,
						].join("\n"),
					);
				}
				this.info(
					`entry: ${measured.sizes.entry} gzip bytes; ${Object.keys(measured.sizes.lazy).length} lazy chunks within their baseline`,
				);
			},
		},
	};
}
