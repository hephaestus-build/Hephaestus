import { z } from "zod";

/**
 * The ids are in the URL, so they are part of the contract with anyone's bookmarks and with the
 * redirects that point here. Renaming one is a breaking change to a link.
 */
const REVIEW_SECTION_IDS = ["how-much", "when-and-where", "past-work"] as const;

export type ReviewSectionId = (typeof REVIEW_SECTION_IDS)[number];

export const REVIEW_SECTIONS = [
	{
		id: "how-much",
		label: "How much",
		description:
			"Set how far reviews go without you for the whole workspace. Override it for a group or a single practice only where it needs something different.",
	},
	{
		id: "when-and-where",
		label: "When and where",
		description:
			"Choose what starts a review, which work it may look at, and how often to keep checking.",
	},
	{
		id: "past-work",
		label: "Past work",
		description:
			"Catch up on work that was already there before reviews were turned on. You get an estimate first, then decide whether to run it.",
	},
] as const satisfies readonly { id: ReviewSectionId; label: string; description: string }[];

export const DEFAULT_REVIEW_SECTION: ReviewSectionId = "how-much";

/**
 * `catch` rather than a hard failure: a stale bookmark naming a section that no longer exists should
 * open the page, not a router error.
 */
export const reviewSearchSchema = z.object({
	section: z.enum(REVIEW_SECTION_IDS).optional().catch(undefined),
	overrides: z.boolean().optional().catch(undefined),
});

export type ReviewSearch = z.infer<typeof reviewSearchSchema>;
