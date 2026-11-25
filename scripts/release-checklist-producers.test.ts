import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { asRecord, readJsonFile } from "./lib/json.ts";

void test("ReleaseChecklistProducersTest", async () => {
	const guide = await readFile("docs/contributor/release-management.mdx", "utf8");
	const checklist = guide.slice(
		guide.indexOf("#### Release check for 1.0"),
		guide.indexOf("#### Sign the 1.0 release plan"),
	);
	const template = asRecord(await readJsonFile("scripts/templates/release-plan.json"), "template");
	const fields = asRecord(template.checks, "template checks");
	const recorded = Array.from(
		checklist.matchAll(/^\| `(?<id>[a-z-]+)` \| /gmu),
		(match) => match.groups?.id ?? "",
	);
	assert.deepEqual(recorded.toSorted(), Object.keys(fields).toSorted());
});
