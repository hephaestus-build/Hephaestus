import assert from "node:assert/strict";
import { test } from "node:test";

import { assertCoverage, testIdentities } from "./verify-server-test-selection.ts";

void test("reads JUnit identities including skipped discovery and parameterized cases", () => {
	assert.deepEqual(
		testIdentities(
			'<testsuite><testcase classname="Example" name="case[1]"><skipped/></testcase><testcase classname="Example" name="case[2]"/></testsuite>',
		),
		new Set(["Example#case[1]", "Example#case[2]"]),
	);
	assert.throws(() => testIdentities("<testsuite>"));
	assert.throws(() => testIdentities('<testsuite><testcase name="missing class"/></testsuite>'));
});
void test("rejects missing tests, overlapping shards, unexpected tests and empty discoveries", () => {
	const inventory = new Set(["a", "b"]);
	assert.doesNotThrow(() => assertCoverage(inventory, [new Set(["a"]), new Set(["b"])], true));
	assert.throws(() => assertCoverage(inventory, [new Set(["a"])], true));
	assert.throws(() => assertCoverage(inventory, [inventory, new Set(["b"])], true));
	assert.throws(() => assertCoverage(inventory, [new Set(["a", "b", "c"])], true));
	assert.throws(() => assertCoverage(new Set(), [new Set()], true));
	assert.throws(() => assertCoverage(inventory, [inventory, new Set()], true));
	assert.doesNotThrow(() => assertCoverage(inventory, [inventory, new Set(["b"])], false));
});
