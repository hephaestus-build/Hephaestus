import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { asRecord, readJsonFile } from "./lib/json.ts";

void test("ReleaseChecklistProducersTest", async () => {
	const guide = await readFile("docs/contributor/release-management.mdx", "utf8");
	const cut = guide.slice(guide.indexOf("### Cut 1.0.0"));
	assert.match(cut, /\]\(#release-check-for-10\)/u);
	const checklist = cut.slice(
		cut.indexOf("#### Release check for 1.0"),
		cut.indexOf("#### Sign the 1.0 release plan"),
	);
	assert.match(checklist, /\[1\.0 release epic\].*\/issues\/4878\)/u);
	assert.doesNotMatch(checklist, /\/issues\/1377\)/u);
	const template = asRecord(await readJsonFile("scripts/templates/release-plan.json"), "template");
	const fields = asRecord(template.checks, "template checks");
	const [freezeTable, planTable] = checklist
		.split("\n\n")
		.filter((block) => block.startsWith("| "));
	assert.ok(freezeTable !== undefined && planTable !== undefined);
	const recorded = new Map(
		planTable.split("\n").flatMap((line) => {
			const key = /^\| `(?<id>[a-z-]+)` \| /u.exec(line)?.groups?.id;
			return key === undefined ? [] : [[key, line] as const];
		}),
	);
	assert.deepEqual([...recorded.keys()], Object.keys(fields));
	for (const id of ["reference-model", "rubric-judge", "judge-spot-check-rule"]) {
		assert.match(recorded.get(id) ?? "", /Before grading/u);
	}
	for (const [key, row] of recorded) {
		if (key !== "freeze") {
			assert.match(row, /hephaestus-build\/Hephaestus\/issues\/\d+/u, key);
		}
	}
	const freezeRows = freezeTable.split("\n").slice(2);
	assert.equal(freezeRows.length, 6);
	for (const row of freezeRows) {
		assert.match(row, /hephaestus-build\/Hephaestus\/issues\/\d+/u);
	}
	assert.match(
		recorded.get("packaged-isolation") ?? "",
		/`PackagedIsolationTest`.*packaged candidate/u,
	);
	assert.match(
		recorded.get("restore-and-recovery") ?? "",
		/external-effect.*packaged candidate.*#5026/u,
	);
	assert.match(recorded.get("restore-and-recovery") ?? "", /packaged Slack recovery.*#4975/u);
	assert.match(recorded.get("first-week") ?? "", /channel-reader and reverse-state.*#5017/u);
});
