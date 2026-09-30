import type { PracticeDeliveryBehavior } from "@/api/types.gen";
import { hasText } from "@/lib/text";

/** What a practice without delivery choices reads as, where a field must say something. */
export const DEFAULT_DELIVERY_BEHAVIOR_TEXT = "No special delivery rules.";

/**
 * A practice's feedback-delivery choices as the sentences an admin reads, one per choice that departs
 * from the default and none when there is no such choice. The editor's fields ask the same three
 * questions; the slugs stay slugs because that is how the editor asks for them.
 */
export function deliveryBehaviorSentences(
	behavior: PracticeDeliveryBehavior | undefined,
): string[] {
	if (behavior === undefined) {
		return [];
	}
	const sentences: string[] = [];
	if (behavior.summaryOnly) {
		sentences.push("Feedback stays in the summary, not on a changed line.");
	}
	if (hasText(behavior.overlapGroup)) {
		sentences.push(
			`On an issue, only the first negative practice in the overlap group “${behavior.overlapGroup}” is shown.`,
		);
	}
	if (hasText(behavior.redundantToSlug)) {
		sentences.push(
			`When this practice and “${behavior.redundantToSlug}” are both negative, feedback from “${behavior.redundantToSlug}” is shown instead.`,
		);
	}
	return sentences;
}
