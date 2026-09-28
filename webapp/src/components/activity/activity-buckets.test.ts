import { describe, expect, it } from "vitest";

import type { ActivityBucket, ActivitySummary } from "@/api/types.gen";

import {
	averagePerBucket,
	bucketLabel,
	bucketSummary,
	deltaPhrase,
	startLabel,
	totalRows,
} from "./activity-buckets";

const ZERO: ActivitySummary = {
	pullRequestsOpened: 0,
	pullRequestsMerged: 0,
	pullRequestsClosed: 0,
	approvals: 0,
	changeRequests: 0,
	commentReviews: 0,
	comments: 0,
	codeComments: 0,
	issuesOpened: 0,
	issuesClosed: 0,
};

const bucket = (day: number, counts: Partial<ActivitySummary>): ActivityBucket => ({
	start: new Date(2026, 8, day),
	summary: { ...ZERO, ...counts },
});

const buckets = [
	bucket(21, { approvals: 1 }),
	bucket(22, { approvals: 2, changeRequests: 1 }),
	bucket(23, { approvals: 3 }),
	bucket(24, {}),
];

const summary = { ...ZERO, approvals: 6, changeRequests: 1 };

/** All of September 2026, which every fixture bucket falls in. */
const span = { from: new Date(2026, 8, 1), to: new Date(2026, 9, 1) };

const reviews = ["REVIEW_APPROVED", "REVIEW_CHANGES_REQUESTED", "REVIEW_COMMENTED"] as const;

describe("bucketSummary", () => {
	it("says the total and the busiest bucket in numbers, the earliest on a tie", () => {
		expect(
			bucketSummary({ bucket: "DAY", buckets, summary }, span, reviews, {
				one: "review",
				many: "reviews",
			}),
		).toBe("7 reviews; busiest day Tuesday 22 September, 3");
	});

	it("names a week and a month the way a sentence does", () => {
		const weekly = { bucket: "WEEK" as const, buckets, summary };
		expect(
			bucketSummary(weekly, span, ["REVIEW_APPROVED"], { one: "approval", many: "approvals" }),
		).toBe("6 approvals; busiest week 23–29 September 2026, 3");
		const monthly = { bucket: "MONTH" as const, buckets: [bucket(1, { approvals: 6 })], summary };
		expect(
			bucketSummary(monthly, span, ["REVIEW_APPROVED"], { one: "approval", many: "approvals" }),
		).toBe("6 approvals; busiest month September 2026, 6");
	});

	it("names no busiest bucket when nothing happened", () => {
		expect(
			bucketSummary({ bucket: "DAY", buckets, summary }, span, ["ISSUE_OPENED"], {
				one: "opened",
				many: "opened",
			}),
		).toBe("0 opened");
	});
});

describe("bucketLabel", () => {
	// A 90-day range from Thursday 25 June to the evening of Wednesday 23 September.
	const range = { from: new Date(2026, 5, 25), to: new Date(2026, 8, 23, 18) };

	it("clips a week the range starts or ends inside to the days it counted", () => {
		expect(bucketLabel(new Date(2026, 5, 22), "WEEK", range)).toBe("25–28 June 2026");
		expect(bucketLabel(new Date(2026, 8, 21), "WEEK", range)).toBe("21–23 September 2026");
		expect(bucketLabel(new Date(2026, 6, 6), "WEEK", range)).toBe("6–12 July 2026");
	});

	it("names a whole month by its name and a partial one by its days", () => {
		expect(bucketLabel(new Date(2026, 6, 1), "MONTH", range)).toBe("July 2026");
		expect(bucketLabel(new Date(2026, 5, 1), "MONTH", range)).toBe("25–30 June 2026");
		expect(bucketLabel(new Date(2026, 8, 1), "MONTH", range)).toBe("1–23 September 2026");
	});

	it("names a day by its weekday", () => {
		expect(bucketLabel(new Date(2026, 8, 22), "DAY", range)).toBe("Tuesday 22 September");
	});
});

describe("totalRows", () => {
	it("gives every bucket a row, zeros included, counting the kinds together", () => {
		expect(totalRows(buckets, ["REVIEW_APPROVED", "REVIEW_CHANGES_REQUESTED"])).toStrictEqual([
			{ start: buckets[0]?.start.getTime(), count: 1 },
			{ start: buckets[1]?.start.getTime(), count: 3 },
			{ start: buckets[2]?.start.getTime(), count: 3 },
			{ start: buckets[3]?.start.getTime(), count: 0 },
		]);
	});
});

describe("deltaPhrase", () => {
	it("sets a count against the period before in words, without a verdict", () => {
		expect(deltaPhrase(7, 3, "the previous 30 days")).toBe("4 more than the previous 30 days");
		expect(deltaPhrase(1, 3, "the previous 7 days")).toBe("2 fewer than the previous 7 days");
		expect(deltaPhrase(5, 5, "the previous 12 months")).toBe("Same as the previous 12 months");
	});
});

describe("averagePerBucket", () => {
	it("writes one decimal, and nothing over no buckets", () => {
		expect(averagePerBucket(18, 30)).toBe("0.6");
		expect(averagePerBucket(0, 0)).toBe("0.0");
	});
});

describe("startLabel", () => {
	it("names the first bucket by its tick in the current year", () => {
		const september = new Date(2026, 8, 27).getTime();
		expect(startLabel(new Date(2026, 8, 21), "DAY", september)).toBe("21 Sep");
		expect(startLabel(new Date(2026, 8, 21), "WEEK", september)).toBe("21 Sep");
	});

	it("gives the start its year when it is not this one", () => {
		const nextYear = new Date(2027, 7, 27).getTime();
		expect(startLabel(new Date(2026, 8, 1), "MONTH", nextYear)).toBe("Sep 2026");
		expect(startLabel(new Date(2026, 8, 21), "DAY", nextYear)).toBe("21 Sep 2026");
	});
});
