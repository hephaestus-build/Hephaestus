import { cn } from "cn";
import type { ReviewFeedback } from "@/api/types.gen";
import { definitionFor, statusToneClass } from "@/components/common/status-def";
import {
	FEEDBACK_LOCATION_DEFS,
	feedbackLocationSentence,
} from "@/components/practice-vocabulary/feedback-location-defs";

export interface FeedbackLocationLabelProps {
	feedback: Pick<ReviewFeedback, "location" | "item">;
	className?: string;
}

/**
 * Where one piece of feedback appears, as the provider's mark and a sentence: "Comment on GitHub
 * pull request #12". The mark is decoration, because the sentence names the provider too.
 */
export function FeedbackLocationLabel({ feedback, className }: FeedbackLocationLabelProps) {
	const def = definitionFor(FEEDBACK_LOCATION_DEFS, feedback.location);
	const Icon = def.icon;
	return (
		<span className={cn("inline-flex max-w-full min-w-0 items-center gap-1.5", className)}>
			<Icon aria-hidden className={cn("size-3.5 shrink-0", statusToneClass(def.badgeVariant))} />
			<span className="min-w-0 break-words">
				{feedbackLocationSentence(feedback.location, feedback.item)}
			</span>
		</span>
	);
}
