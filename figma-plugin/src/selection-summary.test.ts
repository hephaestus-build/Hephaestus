import assert from "node:assert/strict";
import { test } from "node:test";

import { ELEMENT_LIMIT, summarizeSelection } from "./selection-summary.ts";

await test("summarizeSelection: says when nothing is selected", () => {
	assert.deepStrictEqual(summarizeSelection([]), { kind: "EMPTY" });
});

await test("summarizeSelection: counts readable elements, leaves out hidden ones with everything below them, and counts the rest as not captured", () => {
	const section = {
		type: "SECTION",
		children: [
			{ type: "STICKY" },
			{ type: "STICKY", visible: false, children: [{ type: "TEXT" }] },
			{ type: "MEDIA" },
			{ type: "TABLE", children: [{ type: "TABLE_CELL" }, { type: "TABLE_CELL" }] },
		],
	};
	assert.deepStrictEqual(summarizeSelection([section]), {
		kind: "READY",
		selected: 1,
		elements: 5,
		notCaptured: 1,
		hidden: 1,
	});
});

await test("summarizeSelection: says when the selection holds nothing the capture reads", () => {
	assert.deepStrictEqual(summarizeSelection([{ type: "MEDIA" }, { type: "EMBED" }]), {
		kind: "UNSUPPORTED",
		notCaptured: 2,
	});
});

await test("summarizeSelection: refuses a selection over the element limit", () => {
	const children = Array.from({ length: ELEMENT_LIMIT }, () => ({ type: "STICKY" }));
	assert.deepStrictEqual(summarizeSelection([{ type: "SECTION", children }]), {
		kind: "TOO_LARGE",
		elements: ELEMENT_LIMIT + 1,
	});
});
