import assert from "node:assert/strict";
import { test } from "node:test";

import { parseUiMessage } from "./messages.ts";

await test("parseUiMessage: accepts exactly a relaunch request", () => {
	assert.deepStrictEqual(parseUiMessage({ type: "relaunch", enabled: true }), {
		type: "relaunch",
		enabled: true,
	});
});

await test("parseUiMessage: ignores anything else, including an extra field", () => {
	for (const value of [
		undefined,
		null,
		"relaunch",
		{ type: "relaunch" },
		{ type: "relaunch", enabled: "yes" },
		{ type: "relaunch", enabled: true, teamId: "t1" },
		{ type: "capture" },
	]) {
		assert.equal(parseUiMessage(value), undefined);
	}
});
