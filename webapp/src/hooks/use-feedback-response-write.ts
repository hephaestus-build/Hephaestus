import {
	type Mutation,
	type QueryClient,
	useMutation,
	useMutationState,
	useQueryClient,
} from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	clearOwnFeedbackResponseMutation,
	replaceOwnFeedbackResponseMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackResponse, FeedbackResponseRequest } from "@/api/types.gen";
import {
	type FeedbackResponseControls,
	isEmptyFeedbackResponse,
	responseRequestOf,
} from "@/components/profile/reviews";
import { filedUnder } from "@/hooks/use-pending-mutation-ids";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

const FEEDBACK_RESPONSE_WRITE_KEY = ["feedback-response", "write"];

/**
 * What either mutation is handed: a replace carries the response, a clear none. Only this hook
 * files mutations under the key, so these are the variables the cache holds for it.
 */
interface FeedbackResponseWriteVariables {
	path: { feedbackId: string };
	body?: FeedbackResponseRequest;
}

const READS_OF_FEEDBACK_RESPONSES: ReadonlySet<string> = new Set([
	"getOwnPracticePageFeedback",
	"getOwnFeedbackResponse",
	"getOwnFeedbackResolutionCounts",
	"getOwnPracticeProfileOverview",
	"listOwnGroupReviews",
	"listOwnReviews",
	"getOwnReview",
	"listOwnItemReviews",
	"listOwnResults",
	"getOwnResult",
	"listReviewFeedback",
	"getReviewFeedback",
	"listReviewResults",
	"getReviewResult",
	"listReviewProblems",
]);

export type FeedbackResponseWrite = FeedbackResponseControls;

/** Said where the server would refuse it: a disagreement is written for an admin to read. */
const DISAGREEMENT_NEEDS_REASON = "Add your reason so a workspace admin can read it.";

/**
 * The developer's response to a piece of feedback, written from any surface. The endpoint
 * replaces both dimensions at once, so every write carries the complete response that stands with
 * the change applied over it. A write stays pending until the reads that carry the response have
 * refetched, so its buttons show the response being written until the refetch carries it, rather
 * than flicker back to the one they showed before.
 */
export function useFeedbackResponseWrite(workspaceSlug: string): FeedbackResponseWrite {
	const queryClient = useQueryClient();
	const written = async () => refreshResponses(queryClient, workspaceSlug);
	const replaceMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, replaceOwnFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) =>
			toast.error(problemDetailOf(error, "We could not save your response. Try again.")),
	});
	const clearMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, clearOwnFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) =>
			toast.error(problemDetailOf(error, "We could not clear your response. Try again.")),
	});
	const pending = useMutationState<
		[string, FeedbackResponseRequest] | undefined,
		Mutation<unknown, Error, FeedbackResponseWriteVariables>
	>({
		filters: { mutationKey: FEEDBACK_RESPONSE_WRITE_KEY, status: "pending" },
		select: ({ state: { variables } }) =>
			variables && [variables.path.feedbackId, variables.body ?? {}],
	});
	// Mutation cache order is oldest-first; the latest write to one feedback record wins.
	const pendingResponses = new Map(pending.filter((entry) => entry !== undefined));
	const responseOf = (feedbackId: string, recorded: FeedbackResponse | null) =>
		pendingResponses.get(feedbackId) ?? responseRequestOf(recorded);
	const clear = (feedbackId: string) => {
		clearMutation.mutate({ path: { workspaceSlug, feedbackId } });
	};

	return {
		write: (feedbackId, recorded, change) => {
			const response = { ...responseOf(feedbackId, recorded), ...change };
			if (isEmptyFeedbackResponse(response)) {
				clear(feedbackId);
				return;
			}
			if (response.resolution === "DISAGREE" && !hasText(response.comment?.trim())) {
				toast.error(DISAGREEMENT_NEEDS_REASON);
				return;
			}
			replaceMutation.mutate({ path: { workspaceSlug, feedbackId }, body: response });
		},
		clear,
		responseOf,
		pendingResponses,
	};
}

async function refreshResponses(queryClient: QueryClient, workspaceSlug: string) {
	return invalidateWorkspaceReads(queryClient, workspaceSlug, READS_OF_FEEDBACK_RESPONSES);
}
