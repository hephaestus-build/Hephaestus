import type { InAppFeedback, PracticeGroup } from "@/api/types.gen";
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
		`Ticks itself once ${count(needed, "piece", "pieces")} of work in a row ${needed === 1 ? "comes" : "come"} back clean`,
	),
];

/**
 * How a piece of feedback closed, if it did. When and by what is the server's to say, in
 * `closedAt` and `closedBy`; the card only words it.
 */
function closureOf(
	feedback: InAppFeedback,
): { at: Date; state: "resolved" | "closed"; condition: FeedbackTextSegment[] } | undefined {
	const { closedAt: at, closedBy } = feedback;
	if (at === undefined || closedBy === undefined) {
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
								...refs(feedback.cleanWork.map((clean) => clean.reviewedWork)),
								text(" came back clean"),
							]
						: [text(`Resolved by the work on ${day}`)],
			};
		}
		case "DEVELOPER": {
			const answer =
				feedback.response?.resolution === "NOT_APPLICABLE" ? "not applicable" : "addressed";
			return { at, state: "resolved", condition: [text(`Marked as ${answer} on ${day}`)] };
		}
		case "PRACTICE_CHANGED": {
			return {
				at,
				state: "closed",
				condition: [text(`Closed on ${day} · the practice's review rules changed`)],
			};
		}
	}
}

/**
 * One piece of feedback from the wire as a card. Each piece of evidence arrives named, linked
 * and with its outcome, so the strip shows exactly what the wire says about it, and the
 * developer's current response rides on the card, so resolving it needs no second query.
 */
export function toFeedbackCard(
	feedback: InAppFeedback,
	groups: PracticeGroup[],
): PracticeFeedbackCardEntry {
	const group = groups.find((candidate) => candidate.slug === feedback.groupSlug);
	const closure = closureOf(feedback);
	const reviewedWork: ReviewedWorkOutcome[] = feedback.evidence.map((evidence) => ({
		ref: evidence.reviewedWork,
		date: evidence.observedAt,
		outcome: evidence.outcome,
	}));
	return {
		feedbackId: feedback.id,
		practiceSlug: feedback.practiceSlug,
		practiceName: feedback.practiceName,
		// A practice in no group leaves the card's group out; the card names it itself.
		group: hasText(feedback.groupSlug)
			? {
					slug: feedback.groupSlug,
					name: feedback.groupName ?? feedback.groupSlug,
					color: group?.color,
					icon: group ? getGroupVisual(group.icon, group.color).Icon : undefined,
				}
			: undefined,
		headline: feedback.headline,
		// The composer's own Markdown; the card renders it and links the work it can vouch for.
		body: feedback.body,
		reviewedWork,
		// The composer writes the step as a clause; the card shows it as a sentence.
		nextStep: capitalise(feedback.nextStep ?? ""),
		condition: closure?.condition ?? cleanCondition(feedback.cleanNeeded),
		cleanWork: feedback.cleanWork.map((clean) => ({
			ref: clean.reviewedWork,
			date: clean.reviewedAt,
		})),
		cleanNeeded: feedback.cleanNeeded,
		state: closure?.state ?? (feedback.readAt ? "open" : "new"),
		timestamp: closure?.at ?? feedback.preparedAt,
	};
}
