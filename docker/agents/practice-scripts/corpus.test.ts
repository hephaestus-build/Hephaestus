/**
 * What each bundled program sends to a review, over the authored corpus. One snapshot per program, so
 * that a library change shows the programs it changes, and a change with no snapshot difference
 * sends the same input. Run with `--test-update-snapshots` to record a change on purpose.
 */
import assert from "node:assert/strict";
import { readdir } from "node:fs/promises";
import path from "node:path";
import { test } from "node:test";

import { CASES } from "./corpus-cases.ts";
import { runBundledProgram } from "./run-bundled.ts";

const SCRIPTS_DIR = path.resolve(
	import.meta.dirname,
	"../../../server/application/src/main/resources/practices/scripts",
);
const SNAPSHOTS_DIR = path.join(import.meta.dirname, "corpus", "snapshots");

const files = await readdir(SCRIPTS_DIR);
const slugs = files
	.filter((file) => file.endsWith(".ts"))
	.map((file) => path.basename(file, ".ts"));

void test("a snapshot exists only for a program that exists", async () => {
	const snapshots = await readdir(SNAPSHOTS_DIR);
	assert.deepEqual(snapshots.toSorted(), slugs.map((slug) => `${slug}.snapshot`).toSorted());
});

const serialize = (value: unknown) => `${JSON.stringify(value, null, "\t")}\n`;

for (const slug of slugs) {
	void test(slug, async (t) => {
		const runs: Record<string, unknown> = {};
		for (const { name, run } of CASES) {
			const { reviews, outcome } = await runBundledProgram(slug, run);
			if (!("error" in outcome)) {
				runs[name] = reviews;
				continue;
			}
			const stopped =
				outcome.error instanceof Error ? outcome.error.message : String(outcome.error);
			// A program that refuses an item kind says nothing about that case.
			if (!stopped.startsWith("the script reads ")) {
				runs[name] = { stopped, reviews };
			}
		}
		t.assert.fileSnapshot(runs, path.join(SNAPSHOTS_DIR, `${slug}.snapshot`), {
			serializers: [serialize],
		});
	});
}
