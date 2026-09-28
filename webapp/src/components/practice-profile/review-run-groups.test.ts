import { describe, expect, it } from "vitest";

import type { ProfileReviewRun } from "@/api/types.gen";

import {
	groupReviewRunsByDay,
	NOT_DATED,
	reviewRunDayLabel,
	reviewRunFoundNothing,
} from "./review-run-groups";

/** A Tuesday evening in September, so "today" and "yesterday" have a calendar edge to cross. */
const NOW = new Date(2026, 8, 22, 21, 30);

const run = (reviewId: string, reviewedAt: Date): ProfileReviewRun => ({
	reviewId,
	reviewedAt,
	reviewedWork: { id: "902", kind: "scm.pull_request", label: "#902" },
	status: "COMPLETED",
	observations: { strengths: 0, problems: 0, notApplicable: 0, undetermined: 0 },
	feedbackDelivered: 0,
	slippedPractices: [],
	mayRequest: false,
});

describe("reviewRunDayLabel", () => {
	it("dates the reader's own day like every other, never as a word", () => {
		expect(reviewRunDayLabel(new Date(2026, 8, 22, 0, 5), NOW)).toBe("Tuesday, 22 September");
		expect(reviewRunDayLabel(new Date(2026, 8, 21, 23, 55), NOW)).toBe("Monday, 21 September");
	});

	it("dates every day of this year without the year", () => {
		expect(reviewRunDayLabel(new Date(2026, 8, 20, 12, 0), NOW)).toBe("Sunday, 20 September");
		expect(reviewRunDayLabel(new Date(2026, 0, 3, 12, 0), NOW)).toBe("Saturday, 3 January");
	});

	it("adds the year once the year has turned", () => {
		expect(reviewRunDayLabel(new Date(2025, 11, 31, 12, 0), NOW)).toBe(
			"Wednesday, 31 December 2025",
		);
	});
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

	it("files a run whose date it cannot read under a heading that claims no day", () => {
		const undated = { ...run("u", new Date("nonsense")), reviewedAt: new Date("nonsense") };
		const days = groupReviewRunsByDay([run("a", new Date(2026, 8, 22, 18, 0)), undated], NOW);
		expect(days.map((day) => day.label)).toStrictEqual(["Tuesday, 22 September", NOT_DATED]);
	});
});

describe("reviewRunFoundNothing", () => {
	it("is true only with no observation counted and no feedback delivered", () => {
		expect(reviewRunFoundNothing(run("a", NOW))).toBe(true);
		expect(
			reviewRunFoundNothing({
				...run("b", NOW),
				observations: { strengths: 1, problems: 0, notApplicable: 0, undetermined: 0 },
			}),
		).toBe(false);
		expect(reviewRunFoundNothing({ ...run("c", NOW), feedbackDelivered: 1 })).toBe(false);
	});
});
