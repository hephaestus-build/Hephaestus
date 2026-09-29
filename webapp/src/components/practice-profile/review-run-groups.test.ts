import { describe, expect, it } from "vitest";

import type { ProfileReviewRun, ReviewedWorkRef } from "@/api/types.gen";

import { groupReviewRunsByDay, reviewOrdinalLabel, runPositionsOnWork } from "./review-run-groups";

const NOW = new Date(2026, 8, 22, 21, 30);

const pullRequest = (id: string): ReviewedWorkRef => ({
	id,
	kind: "scm.pull_request",
	label: `#${id}`,
});

const run = (
	reviewId: string,
	reviewedAt: Date,
	reviewedWork = pullRequest("902"),
): ProfileReviewRun => ({
	reviewId,
	reviewedAt,
	reviewedWork,
	status: "COMPLETED",
	practices: { toImprove: 0, held: 0, notApplicable: 0, undecided: 0 },
	feedbackDelivered: 0,
	slippedPractices: [],
	mayRequest: false,
});

describe("groupReviewRunsByDay", () => {
	it("keeps the wire's order and never repeats a heading", () => {
		const days = groupReviewRunsByDay(
			[
				run("a", new Date(2026, 8, 22, 18, 0)),
				run("b", new Date(2026, 8, 22, 9, 0)),
				run("c", new Date(2026, 8, 21, 9, 0)),
				run("d", new Date(2025, 11, 31, 9, 0)),
			],
			NOW,
		);
		expect(days.map((day) => [day.label, day.runs.map((entry) => entry.reviewId)])).toStrictEqual([
			["Tuesday, 22 September", ["a", "b"]],
			["Monday, 21 September", ["c"]],
			["Wednesday, 31 December 2025", ["d"]],
		]);
	});
});

describe("runPositionsOnWork", () => {
	it("counts each work's reviews from the oldest", () => {
		const positions = runPositionsOnWork([
			run("newer", NOW),
			run("other", NOW, pullRequest("871")),
			run("older", NOW),
		]);
		expect(Object.fromEntries(positions)).toStrictEqual({ newer: 2, other: 1, older: 1 });
	});
});

describe("reviewOrdinalLabel", () => {
	it.each([
		[1, undefined],
		[2, "2nd review"],
		[3, "3rd review"],
		[4, "4th review"],
		[11, "11th review"],
		[12, "12th review"],
		[21, "21st review"],
		[112, "112th review"],
	])("labels position %i as %s", (position, label) => {
		expect(reviewOrdinalLabel(position)).toBe(label);
	});
});
