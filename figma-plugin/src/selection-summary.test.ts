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
			{ type: "TABLE", numRows: 1, numColumns: 2 },
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

/** A table as Figma gives it: no `children`, only its rows and columns (review of #5477). */
await test("summarizeSelection: counts a table and each of its cells", () => {
	assert.deepStrictEqual(summarizeSelection([{ type: "TABLE", numRows: 7, numColumns: 5 }]), {
		kind: "READY",
		selected: 1,
		elements: 1 + 7 * 5,
		notCaptured: 0,
		hidden: 0,
	});
});

await test("summarizeSelection: a table over the element limit is too large", () => {
	assert.equal(
		summarizeSelection([{ type: "TABLE", numRows: ELEMENT_LIMIT / 50, numColumns: 50 }]).kind,
		"TOO_LARGE",
	);
});

await test("summarizeSelection: vector art and freehand drawing yield no element", () => {
	assert.deepStrictEqual(summarizeSelection([{ type: "VECTOR" }, { type: "HIGHLIGHT" }]), {
		kind: "UNSUPPORTED",
		notCaptured: 2,
	});
});

await test("summarizeSelection: an all-hidden selection says so", () => {
	assert.deepStrictEqual(summarizeSelection([{ type: "STICKY", visible: false }]), {
		kind: "ALL_HIDDEN",
		hidden: 1,
	});
});
