import { definitionFor } from "@/components/common/status-def";
import type { FeedbackMode } from "@/lib/practice-feedback-mode";

import { StatusBadge, type StatusBadgeProps } from "@/components/common/StatusBadge";
import { FEEDBACK_MODE_DEFS } from "./feedback-mode-defs";

export interface FeedbackModeBadgeProps extends Omit<StatusBadgeProps, "def"> {
	feedbackMode: FeedbackMode;
}

export function FeedbackModeBadge({ feedbackMode, ...props }: FeedbackModeBadgeProps) {
	return <StatusBadge def={definitionFor(FEEDBACK_MODE_DEFS, feedbackMode)} {...props} />;
}
