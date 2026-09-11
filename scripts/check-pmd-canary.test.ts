import assert from "node:assert/strict";
import { test } from "node:test";

import { pmdResult } from "./check-pmd-canary.ts";

void test("recognizes a clean PMD report", () => {
	assert.deepEqual(pmdResult('<pmd version="7.27.0"/>'), { violations: [], errors: 0 });
});

void test("reads violations across files and repeated violations in one file", () => {
	assert.deepEqual(
		pmdResult(`<pmd version="7.27.0">
  <file name="One.java"><violation rule="UnusedPrivateField"/><violation rule="UnusedPrivateMethod"/></file>
  <file name="Two.java"><violation rule="UnusedPrivateField"/></file>
</pmd>`),
		{ violations: ["UnusedPrivateField", "UnusedPrivateMethod", "UnusedPrivateField"], errors: 0 },
	);
});

void test("distinguishes incomplete analysis and invalid rules from a clean report", () => {
	assert.deepEqual(
		pmdResult(`<pmd version="7.27.0">
  <error filename="Broken.java" msg="Parse error"/>
  <error filename="Other.java" msg="Type resolution error"/>
  <configerror rule="MissingRule" msg="Rule unavailable"/>
</pmd>`),
		{ violations: [], errors: 3 },
	);
});

void test("rejects malformed, missing and incomplete report structures", () => {
	for (const xml of [
		"<pmd>",
		'<testsuite name="not PMD"/>',
		'<pmd version="7.27.0"><file name="One.java"><violation/></file></pmd>',
	])
		assert.throws(() => pmdResult(xml));
});
