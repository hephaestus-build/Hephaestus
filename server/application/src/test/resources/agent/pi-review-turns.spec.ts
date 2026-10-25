import assert from "node:assert/strict";
import test from "node:test";

import {
	missingSlugs,
	planTurns,
	shouldRecordNow,
	spent,
	turnBudget,
} from "../../../main/resources/agent/pi-review-turns.ts";

void test("practices become one turn per catalog group, in index order", () => {
	assert.deepEqual(
		planTurns([
			{ slug: "a", group: "code" },
			{ slug: "b", group: "process" },
			{ slug: "c", group: "code" },
			{ slug: "d" },
		]),
		[
			{ id: "code", slugs: ["a", "c"] },
			{ id: "process", slugs: ["b"] },
			{ id: "d", slugs: ["d"] },
		],
	);
});

void test("a group larger than a turn is split in index order", () => {
	const practices = ["p1", "p2", "p3", "p4", "p5"].map((slug) => ({ slug, group: "g" }));
	assert.deepEqual(planTurns(practices, 2), [
		{ id: "g-1", slugs: ["p1", "p2"] },
		{ id: "g-2", slugs: ["p3", "p4"] },
		{ id: "g-3", slugs: ["p5"] },
	]);
});

void test("blank and duplicate slugs and an unusable turn size are rejected", () => {
	assert.throws(() => planTurns([{ slug: " " }]), /needs a slug/u);
	assert.throws(() => planTurns([{ slug: "a" }, { slug: "a" }]), /duplicate/u);
	assert.throws(() => planTurns([{ slug: "a" }], 0), /maxPerTurn/u);
});

const piece = { modelCalls: 6, outputTokens: 8000 };

void test("a turn's budget is one piece per practice, never less than three, whatever other turns spent", () => {
	assert.deepEqual(turnBudget(6, piece), { modelCalls: 36, outputTokens: 48_000 });
	// A turn of one practice still reads before it records.
	assert.deepEqual(turnBudget(1, piece), { modelCalls: 18, outputTokens: 24_000 });
	assert.deepEqual(turnBudget(3, piece), turnBudget(1, piece));
	assert.throws(() => turnBudget(0, piece), /practices/u);
});

void test("a turn is asked to record with two calls left, or when its tokens only pay for what it owes", () => {
	const budget = turnBudget(3, piece);
	assert.equal(shouldRecordNow({ modelCalls: 10, outputTokens: 1000 }, budget, 3, 1500), false);
	assert.equal(shouldRecordNow({ modelCalls: 16, outputTokens: 1000 }, budget, 3, 1500), true);
	assert.equal(shouldRecordNow({ modelCalls: 4, outputTokens: 19_000 }, budget, 3, 1500), false);
	assert.equal(shouldRecordNow({ modelCalls: 4, outputTokens: 19_600 }, budget, 3, 1500), true);
});

void test("a turn has spent its budget when either its calls or its output tokens are used up", () => {
	const budget = turnBudget(1, piece);
	assert.equal(spent({ modelCalls: 17, outputTokens: 23_999 }, budget), false);
	assert.equal(spent({ modelCalls: 18, outputTokens: 0 }, budget), true);
	assert.equal(spent({ modelCalls: 1, outputTokens: 24_000 }, budget), true);
});

void test("missing practices keep the review's order", () => {
	assert.deepEqual(missingSlugs(["a", "b", "c"], ["c", "a"]), ["b"]);
	assert.deepEqual(missingSlugs(["a"], ["a", "a"]), []);
});
