import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { inventoryIssues, readProjectInventory } from "./context.ts";

void test("an inventory issue with an empty title is held, and one without a title or number is not", async (t) => {
	const dir = await mkdtemp(path.join(tmpdir(), "precompute-context-"));
	t.after(async () => rm(dir, { recursive: true, force: true }));
	await writeFile(
		path.join(dir, "project_inventory.json"),
		JSON.stringify({
			issues: [{ number: 7, title: "", state: "OPEN" }, { number: 8 }, { title: "No number" }],
		}),
	);
	const issues = inventoryIssues(await readProjectInventory(dir));
	assert.deepEqual([...issues.keys()], [7]);
	assert.partialDeepStrictEqual(issues.get(7), { title: "", state: "OPEN" });
});
