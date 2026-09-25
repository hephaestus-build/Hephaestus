import { describe, expect, it } from "vitest";

import type { InAppFeedback, PracticeGroup, ReviewedWorkRef } from "@/api/types.gen";

import { toFeedbackCard } from "./practice-feedback-cards";

const group: PracticeGroup = {
	id: 1,
	slug: "review-ready-work",
	name: "Packaging work for review",
	displayOrder: 0,
	visibleInPracticeDashboards: true,
	autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
	icon: "Package",
	color: "sky",
	createdAt: new Date("2026-01-01T00:00:00Z"),
};

const pullRequest = (n: number): ReviewedWorkRef => ({
	id: String(n),
	kind: "scm.pull_request",
	label: `#${n}`,
	url: `https://github.example/pr/${n}`,
});

/** One clean piece of work on the wire: the ref the card links and the day it was reviewed. */
const cleanWork = (n: number, reviewedAt: string) => ({
	reviewedWork: pullRequest(n),
	reviewedAt: new Date(reviewedAt),
});

const feedback: InAppFeedback = {
	id: "f1",
	headline: "Descriptions name the what, rarely the why",
	body: "#16 and #17 list the files touched.",
	nextStep: "Write one paragraph on the problem.",
	practiceSlug: "describe-what-and-why",
	practiceName: "Describe what changed and why",
	groupSlug: "review-ready-work",
	groupName: "Packaging work for review",
	evidence: [
		{ reviewedWork: pullRequest(6), observedAt: new Date("2026-08-19"), outcome: "OMISSION_GAP" },
		{
			reviewedWork: pullRequest(7),
			observedAt: new Date("2026-08-13"),
			outcome: "COMMISSION_PROBLEM",
		},
	],
	preparedAt: new Date("2026-08-20T10:05:00Z"),
	cleanWork: [cleanWork(8, "2026-08-25")],
	cleanNeeded: 3,
};

const RESOLVED_BY_WORK = {
	resolvedByWorkAt: new Date("2026-09-05T08:00:00Z"),
	cleanWork: [cleanWork(8, "2026-08-25"), cleanWork(9, "2026-08-30"), cleanWork(10, "2026-09-05")],
};

describe("toFeedbackCard", () => {
	it("reads an unread card as new, with each piece of work named, linked and judged as the wire says", () => {
		const card = toFeedbackCard(feedback, [group]);
		expect(card).toMatchObject({
			feedbackId: "f1",
			state: "new",
			headline: "Descriptions name the what, rarely the why",
			// The composer's Markdown reaches the card as it was written; the card renders it.
			body: "#16 and #17 list the files touched.",
			nextStep: "Write one paragraph on the problem.",
			group: { slug: "review-ready-work", name: "Packaging work for review", color: "sky" },
			cleanWork: [{ ref: pullRequest(8), date: "2026-08-25T00:00:00.000Z" }],
			cleanNeeded: 3,
			timestamp: "2026-08-20T10:05:00.000Z",
		});
		expect(card.condition).toStrictEqual([
			{ type: "text", text: "Ticks itself once three pieces of work in a row come back clean" },
		]);
		expect(card.reviewedWork).toStrictEqual([
			{ ref: pullRequest(6), date: "2026-08-19T00:00:00.000Z", outcome: "OMISSION_GAP" },
			{ ref: pullRequest(7), date: "2026-08-13T00:00:00.000Z", outcome: "COMMISSION_PROBLEM" },
		]);
	});

	it("reads a card the work resolved as resolved on that day, naming the clean work", () => {
		const card = toFeedbackCard({ ...feedback, ...RESOLVED_BY_WORK }, [group]);
		expect(card).toMatchObject({
			state: "resolved",
			cleanWork: [
				{ ref: pullRequest(8), date: "2026-08-25T00:00:00.000Z" },
				{ ref: pullRequest(9), date: "2026-08-30T00:00:00.000Z" },
				{ ref: pullRequest(10), date: "2026-09-05T00:00:00.000Z" },
			],
			timestamp: "2026-09-05T08:00:00.000Z",
		});
		expect(card.condition).toStrictEqual([
			{ type: "text", text: "Resolved by the work on 5 September · " },
			{ type: "work", ref: pullRequest(8) },
			{ type: "text", text: ", " },
			{ type: "work", ref: pullRequest(9) },
			{ type: "text", text: " and " },
			{ type: "work", ref: pullRequest(10) },
			{ type: "text", text: " came back clean" },
		]);
	});

	it("lets the earlier of the two resolutions say how it was resolved", () => {
		const addressed = {
			feedbackId: "f1",
			resolution: "ADDRESSED",
			respondedAt: new Date("2026-09-01T14:10:00Z"),
		} as const;
		const byPerson = toFeedbackCard(
			{
				...feedback,
				...RESOLVED_BY_WORK,
				response: addressed,
				resolvedByDeveloperAt: addressed.respondedAt,
			},
			[group],
		);
		expect(byPerson.timestamp).toBe("2026-09-01T14:10:00.000Z");
		expect(byPerson.condition).toStrictEqual([
			{ type: "text", text: "Marked as addressed on 1 September" },
		]);
		const byWork = toFeedbackCard(
			{
				...feedback,
				...RESOLVED_BY_WORK,
				response: { ...addressed, respondedAt: new Date("2026-09-09T14:10:00Z") },
				resolvedByDeveloperAt: new Date("2026-09-09T14:10:00Z"),
			},
			[group],
		);
		expect(byWork.timestamp).toBe("2026-09-05T08:00:00.000Z");
		expect(byWork.condition[0]).toStrictEqual({
			type: "text",
			text: "Resolved by the work on 5 September · ",
		});
	});

	it("reads a read card as open and an addressed one as resolved on the day it was answered", () => {
		const open = toFeedbackCard({ ...feedback, readAt: new Date("2026-08-21") }, [group]);
		expect(open.state).toBe("open");
		const resolved = toFeedbackCard(
			{
				...feedback,
				readAt: new Date("2026-08-21"),
				response: {
					feedbackId: "f1",
					resolution: "ADDRESSED",
					respondedAt: new Date("2026-09-09T14:10:00Z"),
				},
				resolvedByDeveloperAt: new Date("2026-09-09T14:10:00Z"),
			},
			[group],
		);
		// The meter stays where the work left it: marking it addressed fills nothing in.
		expect(resolved).toMatchObject({
			state: "resolved",
			cleanWork: [{ ref: pullRequest(8), date: "2026-08-25T00:00:00.000Z" }],
			timestamp: "2026-09-09T14:10:00.000Z",
		});
		expect(resolved.condition).toStrictEqual([
			{ type: "text", text: "Marked as addressed on 9 September" },
		]);
	});

	it("reads a card the reader marked not applicable as resolved, saying so", () => {
		const card = toFeedbackCard(
			{
				...feedback,
				readAt: new Date("2026-08-21"),
				response: {
					feedbackId: "f1",
					resolution: "NOT_APPLICABLE",
					respondedAt: new Date("2026-09-09T14:10:00Z"),
				},
				resolvedByDeveloperAt: new Date("2026-09-09T14:10:00Z"),
			},
			[group],
		);
		expect(card).toMatchObject({ state: "resolved", timestamp: "2026-09-09T14:10:00.000Z" });
		expect(card.condition).toStrictEqual([
			{ type: "text", text: "Marked as not applicable on 9 September" },
		]);
	});

	it("reads a card whose practice changed as closed on that day, unless it had resolved before", () => {
		const closed = toFeedbackCard(
			{ ...feedback, readAt: new Date("2026-08-21"), practiceChangedAt: new Date("2026-09-02") },
			[group],
		);
		expect(closed).toMatchObject({ state: "closed", timestamp: "2026-09-02T00:00:00.000Z" });
		expect(closed.condition).toStrictEqual([
			{ type: "text", text: "Closed on 2 September · the practice's review rules changed" },
		]);
		const resolvedFirst = toFeedbackCard(
			{ ...feedback, ...RESOLVED_BY_WORK, practiceChangedAt: new Date("2026-09-12") },
			[group],
		);
		expect(resolvedFirst).toMatchObject({
			state: "resolved",
			timestamp: "2026-09-05T08:00:00.000Z",
		});
	});

	it("shows a card written without a next step with an empty band", () => {
		expect(toFeedbackCard({ ...feedback, nextStep: undefined }, [group]).nextStep).toBe("");
	});

	it("leaves a card whose feedback disputes the practice open, on the day it was written", () => {
		const card = toFeedbackCard(
			{
				...feedback,
				readAt: new Date("2026-08-21"),
				response: {
					feedbackId: "f1",
					resolution: "DISPUTED",
					respondedAt: new Date("2026-09-09T14:10:00Z"),
				},
			},
			[group],
		);
		// A dispute is an answer, not a closure: the server dates no resolution, so the card keeps the
		// condition that would tick it.
		expect(card).toMatchObject({ state: "open", timestamp: "2026-08-20T10:05:00.000Z" });
		expect(card.condition).toStrictEqual([
			{ type: "text", text: "Ticks itself once three pieces of work in a row come back clean" },
		]);
	});

	it("leaves the group's colour and icon out when the group is not among those given", () => {
		const card = toFeedbackCard(feedback, []);
		expect(card.group).toMatchObject({
			slug: "review-ready-work",
			name: "Packaging work for review",
		});
		expect(card.group?.color).toBeUndefined();
		expect(card.group?.icon).toBeUndefined();
	});

	it("gives a practice in no group no group at all, so nothing offers to open one", () => {
		const card = toFeedbackCard({ ...feedback, groupSlug: undefined, groupName: undefined }, [
			group,
		]);
		expect(card.group).toBeUndefined();
	});
});
