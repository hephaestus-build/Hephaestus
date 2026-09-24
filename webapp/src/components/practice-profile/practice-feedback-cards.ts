import type { InAppFeedback, PracticeGroup } from "@/api/types.gen";
import { count, type FeedbackTextSegment, refs, text } from "@/components/common/feedback-text";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import type {
	PracticeFeedbackCardEntry,
	ReviewedWorkOutcome,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { formatDay } from "@/lib/dates";
import { capitalise } from "@/lib/text";

/** The line under an open card's next step, with the wire's own count. */
const cleanCondition = (needed: number): FeedbackTextSegment[] => [
	text(
		`Ticks itself once ${count(needed, "piece", "pieces")} of work in a row ${needed === 1 ? "comes" : "come"} back clean`,
	),
];

/**
 * How a piece of feedback closed, if it did — the server's rule, read off the wire: resolved by
 * the work coming back clean, resolved by the reader marking it addressed or not applicable, or
 * closed unresolved because the practice's review rules changed. When more than one happened,
 * the earliest is what closed it, and its date and wording are the card's.
 */
function closureOf(
	feedback: InAppFeedback,
): { at: Date; state: "resolved" | "closed"; condition: FeedbackTextSegment[] } | undefined {
	const { response } = feedback;
	const byReader =
		response?.resolution === "ADDRESSED" || response?.resolution === "NOT_APPLICABLE"
			? {
					at: response.respondedAt ?? feedback.preparedAt,
					state: "resolved" as const,
					condition: [
						text(
							`Marked as ${response.resolution === "ADDRESSED" ? "addressed" : "not applicable"} on ${formatDay(response.respondedAt ?? feedback.preparedAt)}`,
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
								...refs(feedback.cleanWork),
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
		ref: evidence.work,
		date: evidence.observedAt.toISOString(),
		outcome: evidence.outcome,
	}));
	return {
		feedbackId: feedback.id,
		practiceSlug: feedback.practiceSlug,
		practiceName: feedback.practiceName,
		groupSlug: feedback.groupSlug ?? "",
		groupName: feedback.groupName ?? "Unassigned",
		groupColor: group?.color,
		groupIcon: group ? getGroupVisual(group.icon, group.color).Icon : undefined,
		headline: feedback.headline,
		// The composer's own Markdown; the card renders it and links the work it can vouch for.
		body: feedback.body,
		reviewedWork,
		// The composer writes the step as a clause; the card shows it as a sentence.
		nextStep: capitalise(feedback.nextStep ?? ""),
		condition: closure?.condition ?? cleanCondition(feedback.cleanNeeded),
		cleanWork: feedback.cleanWork,
		cleanNeeded: feedback.cleanNeeded,
		state: closure?.state ?? (feedback.readAt ? "open" : "new"),
		timestamp: (closure?.at ?? feedback.preparedAt).toISOString(),
	};
}
