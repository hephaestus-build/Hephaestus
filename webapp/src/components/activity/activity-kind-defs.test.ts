import { describe, expect, it } from "vitest";

import {
	ACTIVITY_CATEGORY_DEFS,
	ACTIVITY_KIND_DEFS,
	ACTIVITY_KINDS,
	actionPhrase,
	countPhrase,
	tallyActions,
} from "./activity-kind-defs";
import { tallyOf } from "./activity-tally";

describe("ACTIVITY_KINDS", () => {
	it("lists every kind the registry defines, in the registry's order", () => {
		expect(ACTIVITY_KINDS).toStrictEqual(Object.keys(ACTIVITY_KIND_DEFS));
	});
});

describe("countPhrase and actionPhrase", () => {
	it("count a comment and name a lifecycle event, in the provider's words", () => {
		expect(countPhrase("CODE_COMMENTED", 1, "GITHUB")).toBe("1 comment on code");
		expect(countPhrase("PULL_REQUEST_MERGED", 3, "GITLAB")).toBe("3 merge requests merged");
		expect(actionPhrase({ kind: "REVIEW_COMMENTED", count: 1 }, "GITHUB")).toBe("commented");
		expect(actionPhrase({ kind: "REVIEW_APPROVED", count: 2 }, "GITHUB")).toBe("approved 2 times");
	});
});

describe("ACTIVITY_CATEGORY_DEFS", () => {
	it("leads reviews with each pull request reviewed once, not with the verdicts", () => {
		const tally = tallyOf(
			{
				activeWeeks: 1,
				comments: 5,
				contributions: 7,
				issuesOpened: 1,
				peopleHelped: 2,
				pullRequestsMerged: 1,
				pullRequestsOpened: 2,
				pullRequestsReviewed: 4,
			},
			{
				approvals: 5,
				changeRequests: 2,
				codeComments: 3,
				commentReviews: 1,
				discussionComments: 2,
				issuesClosed: 0,
				pullRequestsClosed: 0,
			},
		);
		expect(ACTIVITY_CATEGORY_DEFS.reviews.headline.count(tally)).toBe(4);
		expect(tallyActions(tally, ACTIVITY_CATEGORY_DEFS.reviews.chips)).toStrictEqual([
			{ kind: "REVIEW_APPROVED", count: 5 },
			{ kind: "REVIEW_CHANGES_REQUESTED", count: 2 },
			{ kind: "REVIEW_COMMENTED", count: 1 },
		]);
		expect(ACTIVITY_CATEGORY_DEFS.comments.headline.count(tally)).toBe(5);
	});
});
