import assert from "node:assert/strict";
import test from "node:test";

import {
	deriveWindows,
	missingSlugs,
	planTurns,
	turnShare,
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
	assert.throws(() => planTurns([{ slug: " " }]), /needs a slug/);
	assert.throws(() => planTurns([{ slug: "a" }, { slug: "a" }]), /duplicate/);
	assert.throws(() => planTurns([{ slug: "a" }], 0), /maxPerTurn/);
});

void test("composition reserves a share of the budget only when it was requested", () => {
	assert.deepEqual(deriveWindows(100_000, true), { measureMs: 85_000, compositionMs: 15_000 });
	assert.deepEqual(deriveWindows(100_000, false), { measureMs: 100_000, compositionMs: 0 });
	assert.throws(() => deriveWindows(0, true), /budgetMs/);
});

void test("a turn's share is the remainder over the turns still to run, nudged at seventy percent", () => {
	assert.deepEqual(turnShare(90_000, 3), { hardMs: 30_000, softMs: 21_000 });
	assert.deepEqual(turnShare(-5, 1), { hardMs: 0, softMs: 0 });
	assert.throws(() => turnShare(1000, 0), /remainingTurns/);
});

void test("missing practices keep the review's order", () => {
	assert.deepEqual(missingSlugs(["a", "b", "c"], ["c", "a"]), ["b"]);
	assert.deepEqual(missingSlugs(["a"], ["a", "a"]), []);
});
