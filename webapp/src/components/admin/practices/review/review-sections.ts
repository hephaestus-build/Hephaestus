import { z } from "zod";

// Section ids are URL contracts used by bookmarks and deep links.
const REVIEW_SECTION_IDS = ["feedback-mode", "automated-review", "past-work"] as const;

export type ReviewSectionId = (typeof REVIEW_SECTION_IDS)[number];

export const REVIEW_SECTIONS = [
	{
		id: "feedback-mode",
		label: "Feedback mode",
		description:
			"Choose how and when feedback reaches developers in the whole workspace. Set a different mode for a group or a single practice only where it needs one.",
	},
	{
		id: "automated-review",
		label: "Automated review",
		description:
			"Choose the review model, what starts a review, which work Hephaestus may review, and how often to keep checking.",
	},
	{
		id: "past-work",
		label: "Past work",
		description:
			"Catch up on work that was already there before reviews were turned on. You get an estimate first, then decide whether to run it.",
	},
] as const satisfies readonly { id: ReviewSectionId; label: string; description: string }[];

export const DEFAULT_REVIEW_SECTION: ReviewSectionId = "feedback-mode";

/**
 * `catch` rather than a hard failure: a stale bookmark naming a section that no longer exists should
 * open the page, not a router error.
 */
export const reviewSearchSchema = z.object({
	section: z.enum(REVIEW_SECTION_IDS).optional().catch(undefined),
	overrides: z.boolean().optional().catch(undefined),
});
