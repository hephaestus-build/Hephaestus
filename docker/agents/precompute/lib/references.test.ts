import assert from "node:assert/strict";
import test from "node:test";

import { closingReferences, issueNumberReferences } from "./references.ts";

void test("closing references use the same issue-number boundary as ordinary references", () => {
	const text =
		"Fixes #12abc, closes #1.2, resolves #42px. Fixes: #7. Also closes #19! <!-- Fixes #8 -->";
	assert.deepEqual(issueNumberReferences(text), [7, 19]);
	assert.deepEqual(closingReferences(text), [7, 19]);
});
