import type { JourneyCost } from "./journey-cost.ts";

/**
 * What each journey costs today. Lower a number when a change saves it; raise one only as a decision
 * the review of that change can see. `rounds` tracks the wall-clock cost of a cold load: each round is
 * a network round trip the reader waits for before the page shows its content.
 */
export const budgets = {
	practiceProfile: {
		reads: [
			"GET /user",
			"GET /user/consent",
			"GET /user/features",
			"GET /w/members/me",
			"GET /w/mentor/conversation",
			"GET /w/onboarding/me",
			"GET /w/practice-groups/standings",
			"GET /w/practice-profile/overview",
			"GET /w/practices/feedback/practice-page",
			"GET /w/practices/reviewed",
			"GET /w/practices/standings",
			"GET /workspaces",
		],
		rounds: 5,
		moved: ["page"],
	},
	askHephaestus: {
		reads: [
			"GET /user",
			"GET /user/consent",
			"GET /user/features",
			"GET /w/members/me",
			"GET /w/mentor/conversation",
			"GET /w/mentor/entries",
			"GET /w/onboarding/me",
			"GET /workspaces",
		],
		rounds: 5,
		moved: ["page"],
	},
	feedbackApproval: {
		reads: [
			"GET /user",
			"GET /user/consent",
			"GET /user/features",
			"GET /w/connections/catalog",
			"GET /w/members/me",
			"GET /w/members?size=100",
			"GET /w/onboarding/me",
			"GET /w/practice-groups",
			"GET /w/practices",
			"GET /w/practices/reviews/feedback?size=1",
			"GET /w/practices/reviews/feedback?size=25&page=0",
			"GET /w/practices/reviews/feedback?size=5&state=AWAITING_APPROVAL&sort=OLDEST",
			"GET /w/practices/reviews/problems?openOnly=false&size=1",
			"GET /w/practices/reviews/results?size=1",
			"GET /w/practices/reviews?size=1",
			"GET /workspaces",
		],
		rounds: 5,
		moved: ["other", "sidebar"],
	},
	profileToAsk: {
		reads: ["GET /w/mentor/entries"],
		rounds: 1,
		moved: [],
	},
} satisfies Record<string, JourneyCost>;
