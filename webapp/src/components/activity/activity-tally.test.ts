import { format } from "date-fns";
import { afterEach, assert, describe, expect, it, vi } from "vitest";

import type { ActivityPersonDetail } from "@/api/types.gen";

import { bucketLabel } from "./activity-buckets";
import { overviewOf, weekStarts } from "./activity-tally";

describe("weekStarts", () => {
	it("starts at the UTC Monday of the first day and lists every week up to the end", () => {
		expect(
			weekStarts(new Date("2026-09-03T15:00:00Z"), new Date("2026-09-21T00:00:00Z")).map((start) =>
				start.toISOString(),
			),
		).toStrictEqual([
			"2026-08-31T00:00:00.000Z",
			"2026-09-07T00:00:00.000Z",
			"2026-09-14T00:00:00.000Z",
		]);
	});
});

describe("overviewOf", () => {
	afterEach(() => {
		vi.unstubAllEnvs();
	});

	const zero = {
		activeWeeks: 0,
		comments: 0,
		contributions: 0,
		issuesOpened: 0,
		peopleHelped: 0,
		pullRequestsMerged: 0,
		pullRequestsOpened: 0,
		pullRequestsReviewed: 0,
	};
	const none = {
		approvals: 0,
		changeRequests: 0,
		codeComments: 0,
		commentReviews: 0,
		discussionComments: 0,
		issuesClosed: 0,
		pullRequestsClosed: 0,
	};

	it.each(["America/New_York", "Europe/Berlin", "Asia/Tokyo"])(
		"reads a server week as Monday to Sunday in %s",
		(timeZone) => {
			// Node reads a new TZ on the next date it formats.
			vi.stubEnv("TZ", timeZone);
			const monday = new Date("2026-09-21T00:00:00Z");
			const detail: ActivityPersonDetail = {
				from: monday,
				to: new Date("2026-09-28T00:00:00Z"),
				person: { id: 1, login: "ada", name: "Ada", avatarUrl: "", htmlUrl: "" },
				kind: "PERSON",
				counts: { ...zero, contributions: 1 },
				breakdown: none,
				weeks: [{ start: monday, counts: { ...zero, contributions: 1 }, breakdown: none }],
				repositories: [],
			};

			const [week] = overviewOf(detail).weeks;

			assert(week);
			expect(week.tally.contributions).toBe(1);
			expect(format(week.start, "EEEE")).toBe("Monday");
			expect(bucketLabel(week.start, "WEEK")).toBe("21–27 September 2026");
		},
	);
});
