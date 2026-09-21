/**
 * Writes the versions vite-plus bundles into the pnpm catalog, so a vite-plus bump is one edit
 * followed by this command and `vp install`. `gate:toolchain` fails until the catalog agrees with
 * the bundle; Renovate leaves the bundled tools alone, so the catalog moves only this way.
 */
import { readFileSync, writeFileSync } from "node:fs";

import { parseDocument } from "yaml";

import { bundledVersions, CATALOG_FILE } from "./lib/toolchain-pins.ts";

const workspace = parseDocument(readFileSync(CATALOG_FILE, "utf8"));
let changed = 0;
for (const [name, version] of Object.entries(bundledVersions())) {
	if (workspace.getIn(["catalog", name]) === version) {
		continue;
	}
	workspace.setIn(["catalog", name], version);
	changed += 1;
}
if (changed > 0) {
	writeFileSync(CATALOG_FILE, workspace.toString());
}
console.log(
	changed === 0
		? "sync-toolchain-pins: the catalog already states the bundled versions."
		: `sync-toolchain-pins: updated ${changed} catalog entries; run vp install so the lockfile follows.`,
);
