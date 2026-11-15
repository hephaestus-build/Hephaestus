import type {
	MemberReviewReadiness,
	PracticeProfileOverview,
	WorkspaceOnboarding,
} from "@/api/types.gen";
import type { GetStartedStatus } from "@/components/practice-vocabulary/get-started-status-defs";
import { openRequiredLinks } from "@/lib/onboarding-links";

/**
 * The rules that decide Get started's setup steps, from the raw facts, apart from the list's words.
 * The sidebar's pointer loads with every page, so it reads these rules alone and the app's first
 * load stays small. `get-started-rows` builds the list from the same rules.
 */

/** The section's id, so the sidebar's pointer can link to it. */
export const GET_STARTED_ID = "get-started";

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

/** Whether Get started still has a setup step open. */
export function getStartedSetupOpen({
	onboarding,
	review,
}: {
	onboarding: GetStartedOnboarding;
	review: GetStartedReview | undefined;
}): boolean {
	const reviewStatus = review === undefined ? undefined : reviewStepStatus(review, onboarding);
	return (
		choiceStep(onboarding) === "open" ||
		openRequiredLinks(onboarding.links).length > 0 ||
		(reviewStatus !== undefined && reviewStatus !== "done")
	);
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

/**
 * Where the browser keeps the reader's choice to hide the list: one record per account and
 * workspace, so a second person on a shared browser starts from their own. No account, no record.
 */
export function getStartedFoldKey(
	accountId: string | undefined,
	workspaceSlug: string,
): string | undefined {
	return accountId === undefined ? undefined : `get-started:${accountId}:${workspaceSlug}`;
}

/**
 * Whether the route should show Get started: the member onboarding read is in, and a setup step is
 * open. Once shown, a route keeps the list mounted, so the step that closes it is still announced.
 */
export function getStartedApplies(
	onboarding: GetStartedOnboarding | undefined,
	review: GetStartedReview | undefined,
): boolean {
	return onboarding !== undefined && getStartedSetupOpen({ onboarding, review });
}
