import assert from "node:assert/strict";
import test from "node:test";

import {
	CLEARED_OUTPUT,
	deriveWindows,
	FINISHED_TURN_NOTE,
	finishedTurnEdits,
	missingSlugs,
	planTurns,
	shouldRecordNow,
	spent,
	TURN_PRACTICES_MARKER,
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

void test("composition keeps a share of the safety ceiling only when it was requested", () => {
	assert.deepEqual(deriveWindows(100_000, true), { measureMs: 85_000, compositionMs: 15_000 });
	assert.deepEqual(deriveWindows(100_000, false), { measureMs: 100_000, compositionMs: 0 });
	assert.throws(() => deriveWindows(0, true), /budgetMs/u);
});

const unit = { modelCalls: 6, outputTokens: 8000 };

void test("a turn's budget is one unit per practice, never less than three, whatever other turns spent", () => {
	assert.deepEqual(turnBudget(6, unit), { modelCalls: 36, outputTokens: 48_000 });
	// A turn of one practice still reads before it records.
	assert.deepEqual(turnBudget(1, unit), { modelCalls: 18, outputTokens: 24_000 });
	assert.deepEqual(turnBudget(3, unit), turnBudget(1, unit));
	assert.throws(() => turnBudget(0, unit), /practices/u);
});

void test("a turn is asked to record with two calls left, or when its tokens only pay for what it owes", () => {
	const budget = turnBudget(3, unit);
	assert.equal(shouldRecordNow({ modelCalls: 10, outputTokens: 1000 }, budget, 3, 1500), false);
	assert.equal(shouldRecordNow({ modelCalls: 16, outputTokens: 1000 }, budget, 3, 1500), true);
	// 3 observations at 1500 tokens each need 4500 of the 5000 left.
	assert.equal(shouldRecordNow({ modelCalls: 4, outputTokens: 19_000 }, budget, 3, 1500), false);
	assert.equal(shouldRecordNow({ modelCalls: 4, outputTokens: 19_600 }, budget, 3, 1500), true);
});

void test("a turn has spent its budget when either its calls or its output tokens are used up", () => {
	const budget = turnBudget(1, unit);
	assert.equal(spent({ modelCalls: 17, outputTokens: 23_999 }, budget), false);
	assert.equal(spent({ modelCalls: 18, outputTokens: 0 }, budget), true);
	assert.equal(spent({ modelCalls: 1, outputTokens: 24_000 }, budget), true);
});

void test("missing practices keep the review's order", () => {
	assert.deepEqual(missingSlugs(["a", "b", "c"], ["c", "a"]), ["b"]);
	assert.deepEqual(missingSlugs(["a"], ["a", "a"]), []);
});

void test("below the budget the context only grows, so the provider's prompt cache keeps serving it", () => {
	assert.deepEqual(
		finishedTurnEdits(
			[
				{ entryId: "t1", role: "user", text: `## Turn 1 of 2: code\n${TURN_PRACTICES_MARKER} a.` },
				{ entryId: "t1-read", role: "toolResult", text: "x".repeat(5000) },
			],
			10_000,
		),
		[],
	);
});

void test("past the budget every finished turn keeps its opening, heading and the model's messages, and drops its bulk", () => {
	const opening = "Task.\n\n## What was captured\nthe brief\n\n";
	const edits = finishedTurnEdits(
		[
			{
				entryId: "t1",
				role: "user",
				text: `${opening}## Turn 1 of 3: code\n${TURN_PRACTICES_MARKER} a.`,
			},
			{ entryId: "t1-read", role: "toolResult", text: "x".repeat(5000) },
			{ entryId: "t1-call", role: "assistant", text: "Recorded a." },
			{
				entryId: "t2",
				role: "user",
				text: `## Turn 2 of 3: docs\n${TURN_PRACTICES_MARKER} b, c. Criteria…`,
			},
			{ entryId: "t2-read", role: "toolResult", text: "y".repeat(5000) },
			{ entryId: "t2-nudge", role: "user", text: "Record what this turn still owes." },
			{ entryId: "t2-stored", role: "toolResult", text: "stored 2 observation(s)" },
		],
		8000,
	);
	// One edit for all of them: a single break of the cache buys the most room.
	assert.deepEqual(edits, [
		{ targetId: "t1", content: `${opening}## Turn 1 of 3: code\n${FINISHED_TURN_NOTE}` },
		{ targetId: "t1-read", content: CLEARED_OUTPUT },
		{ targetId: "t2", content: `## Turn 2 of 3: docs\n${FINISHED_TURN_NOTE}` },
		{ targetId: "t2-read", content: CLEARED_OUTPUT },
	]);
});

void test("an edited prompt no longer starts a turn, so no turn is edited twice", () => {
	assert.deepEqual(
		finishedTurnEdits(
			[
				{ entryId: "t1", role: "user", text: `## Turn 1 of 1: code\n${FINISHED_TURN_NOTE}` },
				{ entryId: "compose", role: "user", text: "The review just finished." },
				{ entryId: "read", role: "toolResult", text: "z".repeat(5000) },
			],
			100,
		),
		[],
	);
});
