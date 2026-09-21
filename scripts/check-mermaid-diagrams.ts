/**
 * Parses every committed diagram with the mermaid the docs site actually resolves — both the standalone
 * `.mmd` files and the ```mermaid blocks fenced inside `.md` and `.mdx`.
 *
 * Docusaurus renders mermaid in the browser, so a diagram it cannot parse builds green and publishes
 * as an error box on the page. That is how the generated ER schema shipped unreadable: the resolved
 * mermaid rejected `NUMERIC(12,6)`, and nothing in the toolchain had an opinion about it.
 *
 * The fenced blocks are the ones a person edits by hand and the only ones with a way to be wrong, so
 * a version of this gate that reads only the generated `.mmd` file checks almost nothing while still
 * printing a count. Whatever narrows the scope of this file, it must not be that distinction.
 *
 * Two regressions are in scope, which is why this reads the resolved package rather than pinning a
 * version here: a column type the generator emits that mermaid cannot read, and a transitive bump
 * that takes the parser backwards.
 */
import { readdir, readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";

import { JSDOM } from "jsdom";

import { asRecord, asString, readJsonFile } from "./lib/json.ts";

/** Resolved from this file, so the gate answers the same whatever the working directory is. */
const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const ROOTS = [path.join(REPO_ROOT, "docs")];

const dom = new JSDOM("<!DOCTYPE html><body></body>", {
	pretendToBeVisual: true,
});
// Mermaid reads both off the global scope, and this process has neither.
Object.assign(globalThis, { window: dom.window, document: dom.window.document });

const manifest = createRequire(import.meta.url).resolve("mermaid/package.json");
const version = asString(
	asRecord(await readJsonFile(manifest), manifest).version,
	`${manifest} version`,
);
const { default: mermaid } = await import("mermaid");

/** ```mermaid … ``` inside a Markdown or MDX page. Non-greedy, so two blocks on one page stay two. */
const FENCED = /```mermaid[^\n]*\n(?<source>[\s\S]*?)```/gu;

interface Diagram {
	/** Names the page and, for a fenced block, which block on it. */
	readonly label: string;
	readonly source: string;
	readonly requiresAccessibleName: boolean;
}

const diagrams: Diagram[] = [];
for (const root of ROOTS) {
	for (const entry of await readdir(root, { recursive: true })) {
		if (entry.split(path.sep).includes("node_modules")) {
			continue;
		}
		const file = path.join(root, entry);
		if (entry.endsWith(".mmd")) {
			diagrams.push({
				label: file,
				source: await readFile(file, "utf8"),
				requiresAccessibleName: false,
			});
			continue;
		}
		if (!entry.endsWith(".md") && !entry.endsWith(".mdx")) {
			continue;
		}
		const text = await readFile(file, "utf8");
		let block = 0;
		for (const match of text.matchAll(FENCED)) {
			block += 1;
			const source = match.groups?.source;
			if (source === undefined) {
				continue;
			}
			diagrams.push({
				label: `${file} (mermaid block ${block})`,
				source,
				requiresAccessibleName: true,
			});
		}
	}
}

if (diagrams.length === 0) {
	console.error("No diagrams found — this check would pass without checking anything.");
	process.exit(1);
}

let failed = false;
for (const { label, source, requiresAccessibleName } of diagrams) {
	if (
		requiresAccessibleName &&
		(!/^\s*accTitle:\s*\S.+$/mu.test(source) || !/^\s*accDescr:\s*\S.+$/mu.test(source))
	) {
		failed = true;
		console.error(
			`${label}\n  Add non-empty accTitle and accDescr lines so the diagram has an accessible name and description.\n`,
		);
	}
	try {
		await mermaid.parse(source);
	} catch (error) {
		failed = true;
		const message = error instanceof Error ? error.message : String(error);
		console.error(`${label}\n  ${message.split("\n")[0]}\n`);
	}
}

if (failed) {
	console.error(
		`Fix the Mermaid ${version} parse or accessibility errors above before publishing.`,
	);
	process.exit(1);
}

console.log(
	`check-mermaid-diagrams: ${diagrams.length} diagram(s) parse under mermaid ${version}.`,
);
