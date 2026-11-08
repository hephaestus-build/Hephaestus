import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { z } from "zod";

import * as chat from "./kits/chat/index.ts";
import * as docs from "./kits/docs/index.ts";
import * as scm from "./kits/scm/api.ts";
import * as api from "./lib/api.ts";

void test("the author API has exactly the four supported import entries", () => {
	const manifest: unknown = JSON.parse(
		readFileSync(new URL("package.json", import.meta.url), "utf8"),
	);
	const { exports: entries } = z
		.object({ exports: z.record(z.string(), z.string()) })
		.parse(manifest);
	assert.deepEqual(Object.keys(entries).toSorted(), [".", "./chat", "./docs", "./scm"]);
});

void test("the author entry points export functions, not model helpers or record schemas", () => {
	assert.deepEqual(Object.keys(api).toSorted(), [
		"UnratedError",
		"definePracticeScript",
		"listWithin",
	]);
	assert.deepEqual(Object.keys(chat), ["chat"]);
	assert.deepEqual(Object.keys(docs), ["docs"]);
	assert.deepEqual(Object.keys(scm).toSorted(), [
		"branchIssueReferences",
		"checkableItems",
		"dated",
		"isTestPath",
		"issueReferences",
		"issueShape",
		"languageOf",
		"later",
		"scm",
		"subIssueRollup",
	]);
});
