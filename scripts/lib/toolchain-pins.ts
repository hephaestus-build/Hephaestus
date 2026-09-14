/**
 * The tools Vite+ bundles that the repository also pins directly, so the editor, a package script
 * and `vp` all run one version. The pins live in the pnpm catalog and follow the bundle; each
 * entry names the bundled package whose version it takes.
 */
import { readFileSync } from "node:fs";

import { asRecord } from "./json.ts";

export const CATALOG_FILE = "pnpm-workspace.yaml";

/** Catalog entry → the bundled package whose version it restates. */
export const BUNDLED_PINS: Record<string, string> = {
	vite: "vite",
	oxfmt: "oxfmt",
	oxlint: "oxlint",
	"oxlint-tsgolint": "oxlint-tsgolint",
	"@oxlint/plugins": "@oxlint/plugins",
	vitest: "vitest",
	"@vitest/browser": "@vitest/browser",
	"@vitest/browser-playwright": "vitest",
	"@vitest/coverage-v8": "vitest",
	"@vitest/runner": "@vitest/runner",
};

/** The versions the installed vite-plus bundles, by package name. */
export function bundledVersions(): Record<string, string> {
	const dependencies = asRecord(
		asRecord(JSON.parse(readFileSync("node_modules/vite-plus/package.json", "utf8")), "vite-plus")
			.dependencies,
		"vite-plus dependencies",
	);
	return Object.fromEntries(
		Object.entries(BUNDLED_PINS).map(([name, source]) => {
			const pin = dependencies[source];
			if (typeof pin !== "string") throw new Error(`vite-plus does not bundle ${source}`);
			return [name, pin.replace(/^=/, "")];
		}),
	);
}
