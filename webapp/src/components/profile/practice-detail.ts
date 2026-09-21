import type { PracticeStanding, PracticeTrend } from "@/api/types.gen";

/** One practice as the detail levels show it: the catalog's words plus the developer's standing. */
export interface ContributingPractice {
	slug: string;
	name: string;
	whyItMatters?: string;
	whatGoodLooksLike?: string;
	standing?: PracticeStanding["standing"];
	trend?: PracticeTrend;
	/** The first delivered piece of feedback still to act on, when there is one. */
	nextStep?: string;
}
