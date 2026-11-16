import assert from "node:assert/strict";
import { test } from "node:test";

import { missingReleasedPaths } from "./check-docs-released-paths.ts";

const built = new Set([
	"index.html",
	"admin/upgrade.html",
	"admin/practice-review.html",
	"admin/dsms/index.html",
]);
const exists = (file: string) => built.has(file);

void test("a released path passes with a page, a redirect page or a folder index, and fails without one", () => {
	assert.deepEqual(
		missingReleasedPaths(
			"# v1\n/\n/admin/upgrade\n/admin/practice-review\n/admin/dsms\n/admin/gone\n",
			exists,
		),
		["/admin/gone"],
	);
});

void test("comments and blank lines are not paths", () => {
	assert.deepEqual(missingReleasedPaths("# v1.0.0-rc.8\n\n   \n", exists), []);
});
