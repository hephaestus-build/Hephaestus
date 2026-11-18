import assert from "node:assert/strict";
import { test } from "node:test";

import { addedChangesets, isAtFront } from "./queue-version-pr.ts";

void test("only a changeset added after the Version PR's base makes it stale", () => {
	const diff = [
		"A\t.changeset/later-fix.md",
		"M\t.changeset/README.md",
		"A\t.changeset/pre/consumed.md",
		"D\t.changeset/consumed-elsewhere.md",
		"A\t.changeset/config.json",
		"",
	].join("\n");
	assert.deepEqual(addedChangesets(diff), [".changeset/later-fix.md"]);
	assert.deepEqual(addedChangesets(""), []);
});

void test("only the front of the queue counts as a jump", () => {
	assert.equal(isAtFront(1), true);
	assert.equal(isAtFront(4), false);
	assert.equal(isAtFront(undefined), false);
});
