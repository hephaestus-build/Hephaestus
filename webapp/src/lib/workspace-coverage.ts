import type { WorkspaceOnboarding } from "@/api/types.gen";

type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

/**
 * What one workspace has set up within an answer, and the one sentence the setup page places below
 * the cards for it; `sentence` is `undefined` when all of it runs. A page may claim readiness only
 * from the server's word, so an answer the server has no option for counts as set up for neither
 * purpose; No AI needs nothing set up. The wording is the language doc's *not set up here yet*,
 * never *unavailable*, and a partial answer never says the whole choice is missing.
 */
export type WorkspaceCoverage =
	| { level: "covered"; sentence?: undefined }
	| { level: "partial" | "none"; sentence: string };

export function workspaceCoverage(
	data: Pick<WorkspaceOnboarding, "workspaceName" | "aiOptions">,
	choice: MemberAiChoice,
): WorkspaceCoverage {
	if (choice === "NO_AI") {
		return { level: "covered" };
	}
	const option = data.aiOptions.find((entry) => entry.choice === choice);
	const practiceReviews = option?.practiceReviewsReady === true;
	const mentor = option?.mentorReady === true;
	if (!practiceReviews && !mentor) {
		return {
			level: "none",
			sentence: `Nothing in ${data.workspaceName} is set up within this answer yet. You get no AI here until a workspace owner adds a model that fits. Your choice still counts.`,
		};
	}
	if (!mentor) {
		return {
			level: "partial",
			sentence: `Practice reviews run in ${data.workspaceName} within this answer. Heph isn't set up here yet.`,
		};
	}
	if (!practiceReviews) {
		return {
			level: "partial",
			sentence: `Heph runs in ${data.workspaceName} within this answer. Practice reviews aren't set up here yet.`,
		};
	}
	return { level: "covered" };
}
