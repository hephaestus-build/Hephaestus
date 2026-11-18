import type {
	MemberReviewReadiness,
	PracticeProfileOverview,
	WorkspaceOnboarding,
} from "@/api/types.gen";
import type { GetStartedStatus } from "@/components/practice-vocabulary/get-started-status-defs";
import { openRequiredLinks } from "@/lib/onboarding-links";

/**
 * The rules that decide Get started's setup steps, from the raw facts, apart from the list's words.
 * `get-started-rows` builds the list from them.
 */

/** The member onboarding facts that the setup steps read. */
export type GetStartedOnboarding = Pick<
	WorkspaceOnboarding,
	"aiChoiceRequired" | "aiChoice" | "links"
>;

/** What the overview says about the reader's first review. */
export interface GetStartedReview {
	reviewed: boolean;
	reviewRunning: boolean;
	blocker: MemberReviewReadiness["blocker"];
}

/** Whether the AI choice step shows and is still open. */
function choiceStep(onboarding: GetStartedOnboarding): "open" | "done" | undefined {
	if (!onboarding.aiChoiceRequired) {
		return undefined;
	}
	return onboarding.aiChoice === undefined ? "open" : "done";
}

/**
 * The first-review step's status, or no step. The step shows only while the reader can move it: a
 * review is under way, their own work starts one, they can ask for one, or a step above removes the
 * reason. A reason that is a decision, the reader's No AI or a workspace admin's setting, leaves no
 * step: the page's first-run state says what it means, and the list does not wait for it forever.
 */
export function reviewStepStatus(
	review: GetStartedReview,
	onboarding: GetStartedOnboarding,
): GetStartedStatus | undefined {
	if (review.reviewed) {
		return "done";
	}
	if (review.reviewRunning) {
		return "in-progress";
	}
	switch (review.blocker) {
		case null:
		case "ON_REQUEST_ONLY": {
			return "to-do";
		}
		case "CHOICE_REQUIRED": {
			return choiceStep(onboarding) === "open" ? "cannot-start" : "to-do";
		}
		case "NOT_ELIGIBLE": {
			return openRequiredLinks(onboarding.links).length > 0 ? "cannot-start" : undefined;
		}
		case "NO_AI":
		case "PERSON_ERASED":
		case "WORKSPACE_INACTIVE":
		case "REVIEWS_OFF":
		case "OUTSIDE_REVIEW_SCOPE":
		case "NO_MODEL":
		case "NO_PRACTICE": {
			return undefined;
		}
	}
}

/** The overview's facts about the first review, as the setup steps read them. */
export function reviewFromOverview(
	overview: Pick<PracticeProfileOverview, "latestReview" | "activeReview" | "reviewReadiness">,
): GetStartedReview | undefined {
	return overview.reviewReadiness === null
		? undefined
		: {
				reviewed: overview.latestReview !== null,
				reviewRunning: overview.activeReview !== null,
				blocker: overview.reviewReadiness.blocker,
			};
}
