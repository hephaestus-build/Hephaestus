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
	closedAt: new Date("2026-09-05T08:00:00Z"),
	closedBy: "WORK" as const,
	cleanWork: [cleanWork(8, "2026-08-25"), cleanWork(9, "2026-08-30"), cleanWork(10, "2026-09-05")],
};

/** The reader's own answer, as the server records the resolution it dates. */
const answered = (resolution: "ADDRESSED" | "NOT_APPLICABLE" | "DISPUTED") => ({
	readAt: new Date("2026-08-21"),
	response: { feedbackId: "f1", resolution, respondedAt: new Date("2026-09-09T14:10:00Z") },
});

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
			cleanWork: [{ ref: pullRequest(8), date: new Date("2026-08-25") }],
			cleanNeeded: 3,
			timestamp: new Date("2026-08-20T10:05:00Z"),
		});
		expect(card.condition).toStrictEqual([
			{ type: "text", text: "Ticks itself once three pieces of work in a row come back clean" },
		]);
		expect(card.reviewedWork).toStrictEqual([
			{ ref: pullRequest(6), date: new Date("2026-08-19"), outcome: "OMISSION_GAP" },
			{ ref: pullRequest(7), date: new Date("2026-08-13"), outcome: "COMMISSION_PROBLEM" },
		]);
	});

	it("reads a card the work closed as resolved on that day, naming the clean work", () => {
		const card = toFeedbackCard({ ...feedback, ...RESOLVED_BY_WORK }, [group]);
		expect(card).toMatchObject({
			state: "resolved",
			resolvedBy: "WORK",
			timestamp: new Date("2026-09-05T08:00:00Z"),
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

	it("reads a read card as open and one the reader closed as resolved, in the words of their answer", () => {
		expect(toFeedbackCard({ ...feedback, readAt: new Date("2026-08-21") }, [group]).state).toBe(
			"open",
		);
		const closedAt = new Date("2026-09-09T14:10:00Z");
		const addressed = toFeedbackCard(
			{ ...feedback, ...answered("ADDRESSED"), closedAt, closedBy: "DEVELOPER" },
			[group],
		);
		// The meter stays where the work left it: marking it addressed fills nothing in.
		expect(addressed).toMatchObject({
			state: "resolved",
			resolvedBy: "DEVELOPER",
			cleanWork: [{ ref: pullRequest(8), date: new Date("2026-08-25") }],
			timestamp: closedAt,
		});
		expect(addressed.condition).toStrictEqual([
			{ type: "text", text: "Marked as addressed on 9 September" },
		]);
		const notApplicable = toFeedbackCard(
			{ ...feedback, ...answered("NOT_APPLICABLE"), closedAt, closedBy: "DEVELOPER" },
			[group],
		);
		expect(notApplicable.condition).toStrictEqual([
			{ type: "text", text: "Marked as not applicable on 9 September" },
		]);
	});

	it("reads a card the practice change closed as closed, saying why", () => {
		const closed = toFeedbackCard(
			{
				...feedback,
				readAt: new Date("2026-08-21"),
				closedAt: new Date("2026-09-02"),
				closedBy: "PRACTICE_CHANGED",
			},
			[group],
		);
		expect(closed).toMatchObject({ state: "closed", timestamp: new Date("2026-09-02") });
		expect(closed.condition).toStrictEqual([
			{ type: "text", text: "Closed on 2 September · the practice's review rules changed" },
		]);
	});

	it("shows a card written without a next step with an empty band", () => {
		expect(toFeedbackCard({ ...feedback, nextStep: undefined }, [group]).nextStep).toBe("");
	});

	it("leaves a card the reader disputed open, on the day it was written", () => {
		// A dispute is an answer, not a closure: the server closes nothing, so the card keeps the
		// condition that would tick it.
		const card = toFeedbackCard({ ...feedback, ...answered("DISPUTED") }, [group]);
		expect(card).toMatchObject({ state: "open", timestamp: new Date("2026-08-20T10:05:00Z") });
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
