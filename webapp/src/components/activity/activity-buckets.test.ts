import { describe, expect, it } from "vitest";

import type { ActivityBucket, ActivitySummary } from "@/api/types.gen";

import { bucketLabel, bucketRows, bucketSummary, kindSeries } from "./activity-buckets";

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

describe("bucketRows", () => {
	it("gives every bucket a row, zeros included, with a column per kind", () => {
		expect(bucketRows(buckets, ["REVIEW_APPROVED", "REVIEW_CHANGES_REQUESTED"])).toStrictEqual([
			{ start: buckets[0]?.start.getTime(), REVIEW_APPROVED: 1, REVIEW_CHANGES_REQUESTED: 0 },
			{ start: buckets[1]?.start.getTime(), REVIEW_APPROVED: 2, REVIEW_CHANGES_REQUESTED: 1 },
			{ start: buckets[2]?.start.getTime(), REVIEW_APPROVED: 3, REVIEW_CHANGES_REQUESTED: 0 },
			{ start: buckets[3]?.start.getTime(), REVIEW_APPROVED: 0, REVIEW_CHANGES_REQUESTED: 0 },
		]);
	});
});

describe("kindSeries", () => {
	it("paints each kind in its tone and lightens a tone the stack already used", () => {
		expect(kindSeries(["COMMENTED", "CODE_COMMENTED"])).toStrictEqual([
			{ kind: "COMMENTED", fill: "var(--color-provider-muted-foreground)", fillOpacity: 1 },
			{ kind: "CODE_COMMENTED", fill: "var(--color-provider-muted-foreground)", fillOpacity: 0.45 },
		]);
	});
});
