import { describe, expect, it } from "vitest";

import type { Reviewer, WorkItem } from "@/api/types.gen";

import { groupOpenWork, OPEN_WORK_GROUPS } from "./open-work-groups";

const person = (login: string) => ({
	id: login.length,
	login,
	name: login,
	avatarUrl: "",
	htmlUrl: `https://github.com/${login}`,
});

const reviewer = (login: string, state: Reviewer["state"]): Reviewer => ({
	user: person(login),
	state,
});

function pullRequest(id: number, overrides: Partial<WorkItem> = {}): WorkItem {
	return {
		id,
		type: "PULL_REQUEST",
		number: id,
		title: `#${id}`,
		state: "OPEN",
		isDraft: false,
		...overrides,
	};
}

/** The group each pull request landed in, by id. */
function groupsOf(work: {
	reviewRequests?: WorkItem[];
	pullRequests?: WorkItem[];
}): Record<number, string> {
	const groups = groupOpenWork(
		{
			reviewRequests: { content: work.reviewRequests ?? [], hasMore: false },
			pullRequests: { content: work.pullRequests ?? [], hasMore: false },
		},
		"ada",
	);
	return Object.fromEntries(
		OPEN_WORK_GROUPS.flatMap((group) => groups[group].map((item) => [item.id, group])),
	);
}

describe("groupOpenWork", () => {
	it("keeps a review request that nobody has settled as one that needs you", () => {
		expect(
			groupsOf({
				reviewRequests: [
					pullRequest(1, { reviewers: [reviewer("ada", "REQUESTED")] }),
					pullRequest(2, {
						reviewers: [reviewer("bob", "COMMENTED"), reviewer("ada", "REQUESTED")],
					}),
					pullRequest(3, {
						reviewDecision: "REVIEW_REQUIRED",
						reviewers: [reviewer("bob", "APPROVED")],
					}),
				],
			}),
		).toStrictEqual({ 1: "review-requested", 2: "review-requested", 3: "review-requested" });
	});

	it("folds a review request away once it is approved or another reviewer asked for changes", () => {
		expect(
			groupsOf({
				reviewRequests: [
					pullRequest(1, { reviewDecision: "APPROVED" }),
					pullRequest(2, {
						reviewers: [reviewer("chen", "CHANGES_REQUESTED"), reviewer("ada", "REQUESTED")],
					}),
				],
			}),
		).toStrictEqual({ 1: "covered", 2: "covered" });
	});

	it("keeps a request you already approved or asked changes on apart, as reviewed by you", () => {
		expect(
			groupsOf({
				reviewRequests: [
					pullRequest(1, { reviewers: [reviewer("ada", "APPROVED")] }),
					pullRequest(2, {
						reviewDecision: "APPROVED",
						reviewers: [reviewer("ada", "CHANGES_REQUESTED"), reviewer("bob", "APPROVED")],
					}),
					// A comment is not a verdict: the request still needs you.
					pullRequest(3, { reviewers: [reviewer("ada", "COMMENTED")] }),
					// Asked again after a verdict, as GitHub records it: a plain request.
					pullRequest(4, { reviewers: [reviewer("ada", "REQUESTED")] }),
					// Someone else's verdict never counts as yours.
					pullRequest(5, {
						reviewers: [reviewer("bob", "APPROVED"), reviewer("ada", "REQUESTED")],
					}),
				],
			}),
		).toStrictEqual({
			1: "reviewed",
			2: "reviewed",
			3: "review-requested",
			4: "review-requested",
			5: "review-requested",
		});
	});

	it("returns your pull request to you on a request for changes or a failing check", () => {
		expect(
			groupsOf({
				pullRequests: [
					pullRequest(1, { reviewDecision: "CHANGES_REQUESTED" }),
					pullRequest(2, { reviewers: [reviewer("bob", "CHANGES_REQUESTED")] }),
					pullRequest(3, { checks: "FAILURE", reviewDecision: "APPROVED" }),
				],
			}),
		).toStrictEqual({ 1: "returned", 2: "returned", 3: "returned" });
	});

	it("reads an approval from the decision, and from the reviewers only where there is none", () => {
		expect(
			groupsOf({
				pullRequests: [
					pullRequest(1, { reviewDecision: "APPROVED" }),
					pullRequest(2, {
						reviewers: [reviewer("bob", "APPROVED"), reviewer("chen", "REQUESTED")],
					}),
					pullRequest(3, {
						reviewDecision: "REVIEW_REQUIRED",
						reviewers: [reviewer("bob", "APPROVED")],
					}),
				],
			}),
		).toStrictEqual({ 1: "approved", 2: "approved", 3: "waiting" });
	});

	it("keeps drafts apart, whatever their checks say", () => {
		expect(
			groupsOf({
				pullRequests: [
					pullRequest(1, { isDraft: true, checks: "FAILURE" }),
					pullRequest(2, { reviewers: [reviewer("bob", "REQUESTED")] }),
				],
			}),
		).toStrictEqual({ 1: "drafts", 2: "waiting" });
	});

	it("puts every pull request in exactly one group, in the order the server listed them", () => {
		const reviewRequests = [pullRequest(1), pullRequest(2, { reviewDecision: "APPROVED" })];
		const pullRequests = [pullRequest(3, { isDraft: true }), pullRequest(4), pullRequest(5)];
		const groups = groupOpenWork(
			{
				reviewRequests: { content: reviewRequests, hasMore: false },
				pullRequests: { content: pullRequests, hasMore: false },
			},
			"ada",
		);
		expect(OPEN_WORK_GROUPS.flatMap((group) => groups[group])).toHaveLength(5);
		expect(groups.waiting.map((work) => work.id)).toStrictEqual([4, 5]);
	});
});
