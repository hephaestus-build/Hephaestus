import { describe, expect, it } from "vitest";

import {
	averagePerBucket,
	bucketLabel,
	deltaPhrase,
	startLabel,
	weekRows,
	weeksSummary,
} from "./activity-buckets";
import { type ActivityTally, tallyOf } from "./activity-tally";

const ZERO = tallyOf(
	{
		activeWeeks: 0,
		comments: 0,
		contributions: 0,
		issuesOpened: 0,
		peopleHelped: 0,
		pullRequestsMerged: 0,
		pullRequestsOpened: 0,
		pullRequestsReviewed: 0,
	},
	{
		approvals: 0,
		changeRequests: 0,
		codeComments: 0,
		commentReviews: 0,
		discussionComments: 0,
		issuesClosed: 0,
		pullRequestsClosed: 0,
	},
);

const week = (day: number, counts: Partial<ActivityTally>) => ({
	start: new Date(2026, 8, day),
	tally: { ...ZERO, ...counts },
});

const weeks = [
	week(7, { REVIEW_APPROVED: 1 }),
	week(14, { REVIEW_APPROVED: 2, pullRequestsReviewed: 3 }),
	week(21, { REVIEW_APPROVED: 3, pullRequestsReviewed: 3 }),
	week(28, {}),
];

const tally = { ...ZERO, REVIEW_APPROVED: 6, pullRequestsReviewed: 6 };

/** All of September 2026 and the first days of October. */
const span = { from: new Date(2026, 8, 7), to: new Date(2026, 9, 5) };

describe("weeksSummary", () => {
	it("says the total and the busiest week in numbers, the earliest on a tie", () => {
		expect(
			weeksSummary({ tally, weeks }, span, (counted) => counted.pullRequestsReviewed, {
				one: "pull request reviewed",
				many: "pull requests reviewed",
			}),
		).toBe("6 pull requests reviewed. Busiest week 14–20 September 2026, 3");
	});

	it("names no busiest week when nothing happened", () => {
		expect(
			weeksSummary({ tally, weeks }, span, (counted) => counted.ISSUE_OPENED, {
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

describe("weekRows", () => {
	it("gives every week a row, zeros included", () => {
		expect(weekRows(weeks, (counted) => counted.REVIEW_APPROVED)).toStrictEqual([
			{ start: weeks[0]?.start.getTime(), count: 1 },
			{ start: weeks[1]?.start.getTime(), count: 2 },
			{ start: weeks[2]?.start.getTime(), count: 3 },
			{ start: weeks[3]?.start.getTime(), count: 0 },
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
