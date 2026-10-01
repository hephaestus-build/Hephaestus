import type { PracticeReviewFields } from "@/components/admin/practice-editor/review-settings";

export type ActorRole = NonNullable<PracticeReviewFields["subject"]>;

/**
 * Who a practice judges on its work, in words. A plain map rather than a `StatusDefs` registry: the
 * role is a setting a practice was given, not a state anything is in, so it has no badge to draw.
 * Keyed on the wire union, so a role the server adds fails the build here rather than reaching an
 * admin as a constant name.
 */
export const ACTOR_ROLE_LABELS: Record<ActorRole, string> = {
	AUTHOR: "Author",
	ASSIGNEE: "Assignee",
	REVIEWER: "Reviewer",
	MERGER: "Whoever merged it",
};
