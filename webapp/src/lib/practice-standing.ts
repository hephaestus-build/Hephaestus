import type {
	PracticeGroupTrend,
	PracticeStanding,
	PracticeTrend,
	ReviewedPractice,
} from "@/api/types.gen";

export function nextStepOf(practiceStanding?: PracticeStanding): string | undefined {
	const firstAction = practiceStanding?.toWorkOn[0];
	if (!firstAction) return undefined;
	const deliveredGuidance = firstAction.deliveredFeedback?.trim();
	const observationTitle = firstAction.title.trim();
	const distinctTitle =
		observationTitle !== practiceStanding.name.trim() ? observationTitle : undefined;
	return [deliveredGuidance, distinctTitle].find((value) => value !== undefined && value !== "");
}

export interface ContributingPractice {
	slug: string;
	name: string;
	whyItMatters?: string;
	whatGoodLooksLike?: string;
	standing?: PracticeStanding["standing"];
	trend?: PracticeTrend;
	nextStep?: string;
}

export function contributingPractices(
	groupSlug: string,
	practices: ReviewedPractice[],
	standings: PracticeStanding[],
	trend?: PracticeGroupTrend,
): ContributingPractice[] {
	return practices
		.filter((practice) => practice.groupSlug === groupSlug)
		.map((practice) => {
			const standing = standings.find(
				(candidate) => candidate.groupSlug === groupSlug && candidate.slug === practice.slug,
			);
			return {
				...practice,
				standing: standing?.standing,
				trend: trend?.practices.find((candidate) => candidate.slug === practice.slug),
				nextStep: nextStepOf(standing),
			};
		});
}
