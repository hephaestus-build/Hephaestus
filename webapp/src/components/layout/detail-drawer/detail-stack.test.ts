import { describe, expect, it } from "vitest";

import {
	detailEntryState,
	detailStackSchema,
	openInStack,
	parseDetailStack,
	pushedLevels,
} from "./detail-stack";

const KINDS = ["group", "practice"] as const;
const parse = (detail: unknown) => detailStackSchema(KINDS).parse({ detail }).detail;

describe("detailStackSchema", () => {
	it("coerces the single-value form a hand-written URL produces", () => {
		expect(parse("group:code-review")).toStrictEqual(["group:code-review"]);
	});

	it("keeps an id containing a colon, splitting only at the first one", () => {
		expect(parseDetailStack(parse("practice:scm:pull-request"), KINDS)).toStrictEqual([
			{ kind: "practice", id: "scm:pull-request" },
		]);
	});

	it("drops kinds the surface cannot render, rather than passing them on", () => {
		expect(parse(["practice:a", "sabotage:b", "group:c"])).toStrictEqual(["practice:a", "group:c"]);
	});

	it.each([[":x"], ["x:"], ["nocolon"], [""]])("drops the malformed entry %j", (value) => {
		expect(parse([value])).toStrictEqual([]);
	});

	it("drops a repeat, because the same thing twice is never a stack", () => {
		expect(parse(["practice:a", "practice:a"])).toStrictEqual(["practice:a"]);
	});

	it("caps depth so a hand-written URL cannot mount an unbounded number of drawers", () => {
		const long = Array.from({ length: 50 }, (_, index) => `practice:p${index}`);
		expect(parse(long)).toHaveLength(4);
	});

	it("reads an absent param as a closed stack", () => {
		expect(parseDetailStack(parse(undefined), KINDS)).toStrictEqual([]);
	});
});

describe("openInStack", () => {
	it("appends a level that is not open, as a push", () => {
		expect(openInStack(["feedback:f"], "result:o")).toStrictEqual({
			detail: ["feedback:f", "result:o"],
			pushed: true,
		});
	});

	it("closes down to a level that is already open, rather than repeating it", () => {
		expect(openInStack(["feedback:f", "result:o", "review:r"], "result:o")).toStrictEqual({
			detail: ["feedback:f", "result:o"],
			pushed: false,
		});
	});

	it("swaps the top level of a full stack, so the link still opens what it names", () => {
		const full = ["practice:p", "result:o", "feedback:f", "review:r"];
		expect(openInStack(full, "work:pull-request:1")).toStrictEqual({
			detail: ["practice:p", "result:o", "feedback:f", "work:pull-request:1"],
			pushed: false,
		});
	});

	it("replaces the level in front when asked to swap, as a queue steps to its next record", () => {
		expect(openInStack(["practice:p", "feedback:a"], "feedback:b", { swap: true })).toStrictEqual({
			detail: ["practice:p", "feedback:b"],
			pushed: false,
		});
	});

	it("replaces the level in front on a one-level surface, and that is not a push", () => {
		expect(openInStack(["feedback:a"], "result:o", { single: true })).toStrictEqual({
			detail: ["result:o"],
			pushed: false,
		});
	});

	it("opens the first level of an empty stack even when asked to swap", () => {
		expect(openInStack([], "feedback:a", { swap: true })).toStrictEqual({
			detail: ["feedback:a"],
			pushed: true,
		});
	});
});

describe("pushedLevels", () => {
	it("counts no level on an entry this visit did not push", () => {
		expect(pushedLevels(undefined)).toBe(0);
		expect(pushedLevels({ detailPush: false, detailPushes: 2 })).toBe(0);
	});

	it("counts the pushes in a row, or the entry's own push when it was stamped without a count", () => {
		expect(pushedLevels({ detailPush: true })).toBe(1);
		expect(pushedLevels({ detailPush: true, detailPushes: 2 })).toBe(2);
	});
});

describe("detailEntryState", () => {
	it("counts a push on from the entry it came from, without copying that entry's marks", () => {
		const first = detailEntryState({ focusSwitch: true, detailPush: false }, true, false);
		expect(first).toStrictEqual({ detailPush: true, detailPushes: 1 });
		const second = detailEntryState({ ...first, focusSwitch: true }, true, false);
		expect(second).toStrictEqual({ detailPush: true, detailPushes: 2 });
		expect(pushedLevels(second)).toBe(2);
	});

	it("starts the count over on a new entry that pushed nothing", () => {
		expect(detailEntryState({ detailPush: true, detailPushes: 3 }, false, false)).toStrictEqual({
			detailPush: false,
			detailPushes: undefined,
		});
	});

	it("keeps the marks of an entry rewritten in place, and its count only while it is a push", () => {
		const rewritten = detailEntryState(
			{ focusSwitch: true, detailPush: true, detailPushes: 2 },
			false,
			true,
		);
		expect(rewritten).toMatchObject({ focusSwitch: true, detailPush: false });
		expect(pushedLevels(rewritten)).toBe(0);
	});
});
