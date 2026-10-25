import { describe, expect, it } from "vitest";

import type {
	FeedbackResponse,
	GroupRef,
	ItemRef,
	PracticePageCleanWork,
	PracticePageEvidence,
	PracticePageFeedback,
} from "@/api/types.gen";

import { toFeedbackCard } from "./practice-feedback-cards";

const group: GroupRef = {
	slug: "review-ready-work",
	name: "Packaging work for review",
	icon: "Package",
	color: "sky",
};

const pullRequest = (n: number): ItemRef => ({
	id: String(n),
	kind: "scm.pull_request",
	parentId: null,
	provider: "GITHUB",
	label: `#${n}`,
	title: null,
	url: `https://github.example/pr/${n}`,
	container: null,
	path: null,
	removedAt: null,
});

const recorded = (n: number) => ({
	resultId: `0b9c6f0e-0000-4000-8000-0000000000${String(n).padStart(2, "0")}`,
	reviewId: `5d1e2a3b-0000-4000-8000-0000000000${String(n).padStart(2, "0")}`,
});

const cleanWork = (n: number, recordedAt: string): PracticePageCleanWork => ({
	...recorded(n),
	item: pullRequest(n),
	recordedAt: new Date(recordedAt),
});

const evidence = (n: number, recordedAt: string): PracticePageEvidence => ({
	...recorded(n),
	item: pullRequest(n),
	recordedAt: new Date(recordedAt),
	summary: null,
});

const feedback: PracticePageFeedback = {
	unverifiedHistory: false,
	id: "f1",
	headline: "Descriptions name the what, rarely the why",
	body: "#16 and #17 list the files touched.",
	nextStep: "Write one paragraph on the problem.",
	practiceSlug: "describe-what-and-why",
	practiceName: "Describe what changed and why",
	groupSlug: "review-ready-work",
	groupName: "Packaging work for review",
	evidence: [evidence(6, "2026-08-19"), evidence(7, "2026-08-13")],
	preparedAt: new Date("2026-08-20T10:05:00Z"),
	cleanWork: [cleanWork(8, "2026-08-25")],
	cleanNeeded: 3,
	closedAt: null,
	closedBy: null,
	readAt: null,
	response: null,
	whyItMatters: null,
	whatGoodLooksLike: null,
	withdrawn: false,
};

const RESOLVED_BY_WORK = {
	closedAt: new Date("2026-09-05T08:00:00Z"),
	closedBy: "WORK" as const,
	cleanWork: [cleanWork(8, "2026-08-25"), cleanWork(9, "2026-08-30"), cleanWork(10, "2026-09-05")],
};

/** The reader's own answer, as the server records the resolution it dates. */
const answered = (resolution: NonNullable<FeedbackResponse["resolution"]>) => ({
	readAt: new Date("2026-08-21"),
	response: {
		resolution,
		usefulness: null,
		comment: resolution === "DISAGREE" ? "The change was one concern." : null,
		createdAt: new Date("2026-09-09T14:10:00Z"),
	} satisfies FeedbackResponse,
});

describe("toFeedbackCard", () => {
	it("reads an unread card as new, with each piece of work named and linked as the wire says", () => {
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
			{
				type: "text",
				text: "Resolves on its own once three pieces of work in a row come back clean",
			},
		]);
		// Each piece keeps what the review made of it, so the timeline can draw it.
		expect(card.reviewedWork).toStrictEqual([
			{ ref: pullRequest(6), date: new Date("2026-08-19"), outcome: "NOT_MET" },
			{ ref: pullRequest(7), date: new Date("2026-08-13"), outcome: "NOT_MET" },
		]);
	});

	it("reads a card the work closed as resolved on that day, naming the clean work", () => {
		const card = toFeedbackCard({ ...feedback, ...RESOLVED_BY_WORK }, [group]);
		expect(card).toMatchObject({
			state: "resolved",
			timestamp: new Date("2026-09-05T08:00:00Z"),
			preparedAt: feedback.preparedAt,
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

	it("reads a read card as open and one the reader closed as marked, in the words of their answer", () => {
		expect(toFeedbackCard({ ...feedback, readAt: new Date("2026-08-21") }, [group]).state).toBe(
			"open",
		);
		const closedAt = new Date("2026-09-09T14:10:00Z");
		const addressed = toFeedbackCard(
			{ ...feedback, ...answered("ADDRESSED"), closedAt, closedBy: "DEVELOPER" },
			[group],
		);
		// The meter stays where the work left it: marking it addressed fills nothing in.
		// Marked, never resolved: only the work resolves a card, and the next work confirms the answer.
		expect(addressed).toMatchObject({
			state: "marked",
			cleanWork: [{ ref: pullRequest(8), date: new Date("2026-08-25") }],
			timestamp: closedAt,
		});
		expect(addressed.condition).toStrictEqual([
			{ type: "text", text: "Marked as addressed on 9 September. Your next work confirms it." },
		]);
		const notApplicable = toFeedbackCard(
			{ ...feedback, ...answered("NOT_APPLICABLE"), closedAt, closedBy: "DEVELOPER" },
			[group],
		);
		expect(notApplicable.condition).toStrictEqual([
			{
				type: "text",
				text: "Marked as not applicable on 9 September. Your next work confirms it.",
			},
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
			{ type: "text", text: "Closed on 2 September · the practice’s review rules changed" },
		]);
	});

	it("reads a withdrawn card as withdrawn on the day it was written, with none of its words", () => {
		const withdrawn = toFeedbackCard(
			{
				...feedback,
				headline: feedback.practiceName,
				body: null,
				nextStep: null,
				evidence: [],
				cleanWork: [],
				readAt: new Date("2026-08-21"),
				withdrawn: true,
			},
			[group],
		);
		expect(withdrawn).toMatchObject({
			state: "withdrawn",
			timestamp: feedback.preparedAt,
			body: "",
			nextStep: "",
		});
	});

	it("shows a card written without a next step with an empty band", () => {
		expect(toFeedbackCard({ ...feedback, nextStep: null }, [group]).nextStep).toBe("");
	});

	it("leaves a card the reader disagreed with open, on the day it was written", () => {
		// A disagreement is an answer, not a closure: the server closes nothing, so the card keeps
		// the condition that would tick it.
		const card = toFeedbackCard({ ...feedback, ...answered("DISAGREE") }, [group]);
		expect(card).toMatchObject({ state: "open", timestamp: new Date("2026-08-20T10:05:00Z") });
		expect(card.condition).toStrictEqual([
			{
				type: "text",
				text: "Resolves on its own once three pieces of work in a row come back clean",
			},
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
		const card = toFeedbackCard({ ...feedback, groupSlug: null, groupName: null }, [group]);
		expect(card.group).toBeUndefined();
	});
});
