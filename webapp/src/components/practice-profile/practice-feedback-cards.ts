import type { InAppFeedback, PracticeGroup } from "@/api/types.gen";
import { count, type FeedbackTextSegment, refs, text } from "@/components/common/feedback-text";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import type {
	PracticeFeedbackCardEntry,
	ReviewedWorkOutcome,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { formatDay } from "@/lib/dates";
import { capitalise, hasText } from "@/lib/text";

/** The line under an open card's next step, with the wire's own count. */
const cleanCondition = (needed: number): FeedbackTextSegment[] => [
	text(
		`Ticks itself once ${count(needed, "piece", "pieces")} of work in a row ${needed === 1 ? "comes" : "come"} back clean`,
	),
];

/**
 * How a piece of feedback closed, if it did — the server's rule, read off the wire: resolved by
 * the work coming back clean, resolved by the reader's own answer, or closed unresolved because
 * the practice's review rules changed. Which answers resolve is the server's to decide and it
 * dates the one that did; the card reads that date and takes only the wording from the answer.
 * When more than one happened, the earliest is what closed it, and its date and wording are the
 * card's.
 */
function closureOf(
	feedback: InAppFeedback,
): { at: Date; state: "resolved" | "closed"; condition: FeedbackTextSegment[] } | undefined {
	const { response, resolvedByDeveloperAt } = feedback;
	// The server decides whether an answer resolves and says when; the card only picks the wording.
	const byReader = resolvedByDeveloperAt
		? {
				at: resolvedByDeveloperAt,
				state: "resolved" as const,
				condition: [
					text(
						`Marked as ${response?.resolution === "NOT_APPLICABLE" ? "not applicable" : "addressed"} on ${formatDay(resolvedByDeveloperAt)}`,
					),
				],
			}
		: undefined;
	const byWork = feedback.resolvedByWorkAt
		? {
				at: feedback.resolvedByWorkAt,
				state: "resolved" as const,
				condition:
					feedback.cleanWork.length > 0
						? [
								text(`Resolved by the work on ${formatDay(feedback.resolvedByWorkAt)} · `),
								...refs(feedback.cleanWork.map((clean) => clean.reviewedWork)),
								text(" came back clean"),
							]
						: [text(`Resolved by the work on ${formatDay(feedback.resolvedByWorkAt)}`)],
			}
		: undefined;
	const byPractice = feedback.practiceChangedAt
		? {
				at: feedback.practiceChangedAt,
				state: "closed" as const,
				condition: [
					text(
						`Closed on ${formatDay(feedback.practiceChangedAt)} · the practice's review rules changed`,
					),
				],
			}
		: undefined;
	// Ties go to the work, then the reader, then the practice: the order the glossary lists them.
	return [byWork, byReader, byPractice]
		.filter((closure) => closure !== undefined)
		.sort((a, b) => a.at.getTime() - b.at.getTime())[0];
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
		date: evidence.observedAt.toISOString(),
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
			date: clean.reviewedAt.toISOString(),
		})),
		cleanNeeded: feedback.cleanNeeded,
		state: closure?.state ?? (feedback.readAt ? "open" : "new"),
		timestamp: (closure?.at ?? feedback.preparedAt).toISOString(),
	};
}
