import assert from "node:assert/strict";
import { test } from "node:test";

import { breaksForRelease } from "./lib/mobile-api-contract.ts";

const change = (
	operation: string,
	path: string,
	level: number,
	text = "response property removed",
) => ({
	id: "response-required-property-removed",
	text,
	level,
	operation,
	path,
});

void test("reports a breaking change to an operation the release calls", () => {
	const report = [change("GET", "/workspaces/{workspaceSlug}/practices/feedback/in-app", 3)];

	assert.deepEqual(
		breaksForRelease(report, ["GET /workspaces/{workspaceSlug}/practices/feedback/in-app"]),
		["GET /workspaces/{workspaceSlug}/practices/feedback/in-app: response property removed"],
	);
});

void test("ignores a break in an operation the release does not call, and the same path's other methods", () => {
	const report = [change("GET", "/admin/workspaces", 3), change("DELETE", "/user", 3)];

	assert.deepEqual(breaksForRelease(report, ["GET /user"]), []);
});

void test("ignores warnings and information", () => {
	const report = [change("GET", "/user", 2), change("GET", "/user", 1)];

	assert.deepEqual(breaksForRelease(report, ["GET /user"]), []);
});

void test("reads oasdiff's empty report", () => {
	assert.deepEqual(breaksForRelease(null, ["GET /user"]), []);
	assert.deepEqual(breaksForRelease([], ["GET /user"]), []);
});
