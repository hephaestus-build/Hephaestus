import type { GroupRef, PracticePageFeedback } from "@/api/types.gen";
import {
	count,
	type FeedbackTextSegment,
	refs,
	text,
} from "@/components/practice-vocabulary/feedback-text";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import type {
	PracticeFeedbackCardEntry,
	ReviewedWorkOutcome,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { formatDay } from "@/lib/dates";
import { capitalise, hasText } from "@/lib/text";

/** Newest first, by the timestamp the card shows. */
export function newestFirst<T extends { timestamp: Date }>(cards: T[]): T[] {
	return [...cards].sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());
}

/** The line under an open card's next step, with the wire's own count. */
const cleanCondition = (needed: number): FeedbackTextSegment[] => [
	text(
		`Resolves on its own once ${count(needed, "piece", "pieces")} of work in a row ${needed === 1 ? "comes" : "come"} back clean`,
	),
];

/**
 * How a piece of feedback closed, if it did. When and by what is the server's to say, in
 * `closedAt` and `closedBy`; the card only words it.
 */
function closureOf(feedback: PracticePageFeedback):
	| {
			at: Date;
			state: "resolved" | "marked" | "closed";
			condition: FeedbackTextSegment[];
	  }
	| undefined {
	const { closedAt: at, closedBy } = feedback;
	if (at === null || closedBy === null) {
		return undefined;
	}
	const day = formatDay(at);
	switch (closedBy) {
		case "WORK": {
			return {
				at,
				state: "resolved",
				condition:
					feedback.cleanWork.length > 0
						? [
								text(`Resolved by the work on ${day} · `),
								...refs(feedback.cleanWork.map((clean) => clean.item)),
								text(" came back clean"),
							]
						: [text(`Resolved by the work on ${day}`)],
			};
		}
		case "DEVELOPER": {
			const answer =
				feedback.response?.resolution === "NOT_APPLICABLE" ? "not applicable" : "addressed";
			// The reader's claim, not a resolution: the clean work count goes on, and says whether the work
			// bears it out.
			return {
				at,
				state: "marked",
				condition: [text(`Marked as ${answer} on ${day}. Your next work confirms it.`)],
			};
		}
		case "PRACTICE_CHANGED": {
			return {
				at,
				state: "closed",
				condition: [text(`Closed on ${day} · the practice’s review rules changed`)],
			};
		}
	}
}

export function toFeedbackCard(
	feedback: PracticePageFeedback,
	groups: GroupRef[],
): PracticeFeedbackCardEntry {
	const group = groups.find((candidate) => candidate.slug === feedback.groupSlug);
	const { withdrawn } = feedback;
	const closure = withdrawn ? undefined : closureOf(feedback);
	// Practice-page feedback rests only on Not met observations (the server's
	// `PracticePageFeedbackRouter`), so every piece of its evidence needed attention.
	const reviewedWork: ReviewedWorkOutcome[] = feedback.evidence.map((evidence) => ({
		ref: evidence.item,
		date: evidence.recordedAt,
		outcome: "NOT_MET",
	}));
	return {
		feedbackId: feedback.id,
		unverifiedHistory: feedback.unverifiedHistory,
		practiceSlug: feedback.practiceSlug,
		practiceName: feedback.practiceName,
		// A practice in no group leaves the card's group out; the card names it itself.
		group: hasText(feedback.groupSlug)
			? {
					slug: feedback.groupSlug,
					name: feedback.groupName ?? group?.name ?? "Practice group",
					color: group?.color,
					icon: group ? getGroupVisual(group.icon, group.color).Icon : undefined,
				}
			: undefined,
		headline: feedback.headline,
		// The composer's own Markdown; the card renders it and links the work it can vouch for. A
		// withdrawn card arrives without it.
		body: feedback.body ?? "",
		reviewedWork,
		// The composer writes the step as a clause; the card shows it as a sentence.
		nextStep: capitalise(feedback.nextStep ?? ""),
		condition: closure?.condition ?? cleanCondition(feedback.cleanNeeded),
		cleanWork: feedback.cleanWork.map((clean) => ({
			ref: clean.item,
			date: clean.recordedAt,
		})),
		cleanNeeded: feedback.cleanNeeded,
		state: withdrawn ? "withdrawn" : (closure?.state ?? (feedback.readAt ? "open" : "new")),
		// The wire does not say when feedback was withdrawn, so a withdrawn card keeps its own day.
		timestamp: closure?.at ?? feedback.preparedAt,
		preparedAt: feedback.preparedAt,
	};
}
