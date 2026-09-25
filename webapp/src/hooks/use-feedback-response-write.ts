import {
	type Mutation,
	type QueryClient,
	useMutation,
	useMutationState,
	useQueryClient,
} from "@tanstack/react-query";
import { toast } from "sonner";

import {
	deleteFeedbackResponseMutation,
	getInAppFeedbackQueryKey,
	getPracticeProfileOverviewQueryKey,
	listPracticeGroupReviewRunsInfiniteQueryKey,
	replaceFeedbackResponseMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackResponseRequest, ObservationDetail } from "@/api/types.gen";
import { isEmptyFeedbackResponse } from "@/components/profile/review-runs";
import { filedUnder } from "@/hooks/use-pending-mutation-ids";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

/**
 * Every write of a feedback response, whatever surface it was made on, so the rating buttons of
 * the piece of feedback being written wait wherever it is on screen — a card on the page and the
 * same feedback on a practice level are one write.
 */
const FEEDBACK_RESPONSE_WRITE_KEY = ["feedback-response", "write"];

/**
 * What `write` hands either mutation: a replace carries the response, a withdrawal none. Only
 * `write` files mutations under the key, so these are the variables the cache holds for it.
 */
interface FeedbackResponseWriteVariables {
	path: { feedbackId: string };
	body?: FeedbackResponseRequest;
}

/**
 * Every query that carries the developer's response to a piece of feedback: the cards, the
 * overview and — when the feedback's group is known — that group's review-run feed, whose
 * observations carry the response too. A write on any surface refreshes them all, so a card
 * behind the drawer never reads a response the level has already replaced. The feed's key is
 * built with no `query`, which matches the feed at every page size and practice.
 */
async function invalidateFeedbackResponses(
	queryClient: QueryClient,
	workspaceSlug: string,
	groupSlug: string | undefined,
) {
	return Promise.all([
		queryClient.invalidateQueries({
			queryKey: getInAppFeedbackQueryKey({ path: { workspaceSlug } }),
		}),
		queryClient.invalidateQueries({
			queryKey: getPracticeProfileOverviewQueryKey({ path: { workspaceSlug } }),
		}),
		hasText(groupSlug) &&
			queryClient.invalidateQueries({
				queryKey: listPracticeGroupReviewRunsInfiniteQueryKey({
					path: { workspaceSlug, groupSlug },
				}),
			}),
	]);
}

export interface FeedbackResponseWrite {
	/** Writes the complete response; one with nothing left in it is withdrawn. */
	write: (feedbackId: string, response: FeedbackResponseRequest) => void;
	/** Writes the response to the feedback an observation was delivered as; one with none has none. */
	respond: (observation: ObservationDetail, response: FeedbackResponseRequest) => void;
	/**
	 * The response being written to each piece of feedback, from whichever surface; a withdrawal is
	 * the empty response. A surface shows it over the recorded one until the write has landed.
	 */
	pendingResponses: ReadonlyMap<string, FeedbackResponseRequest>;
}

/**
 * The developer's response to a piece of feedback, written from any surface. A write stays pending
 * until the queries that carry the response have refetched, so its buttons show the answer being
 * written until the refetch carries it, rather than flicker back to the one they showed before.
 */
export function useFeedbackResponseWrite(
	workspaceSlug: string,
	groupSlugOf: (feedbackId: string) => string | undefined,
): FeedbackResponseWrite {
	const queryClient = useQueryClient();
	const written = async (_result: unknown, variables: { path: { feedbackId: string } }) =>
		invalidateFeedbackResponses(queryClient, workspaceSlug, groupSlugOf(variables.path.feedbackId));
	const replaceMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, replaceFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) =>
			toast.error(problemDetailOf(error, "Could not save your feedback response")),
	});
	const deleteMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, deleteFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) =>
			toast.error(problemDetailOf(error, "Could not withdraw your feedback response")),
	});
	const pending = useMutationState<
		[string, FeedbackResponseRequest] | undefined,
		Mutation<unknown, Error, FeedbackResponseWriteVariables>
	>({
		filters: { mutationKey: FEEDBACK_RESPONSE_WRITE_KEY, status: "pending" },
		select: ({ state: { variables } }) =>
			variables && [variables.path.feedbackId, variables.body ?? {}],
	});
	const write = (feedbackId: string, response: FeedbackResponseRequest) => {
		if (isEmptyFeedbackResponse(response)) {
			deleteMutation.mutate({ path: { workspaceSlug, feedbackId } });
			return;
		}
		replaceMutation.mutate({ path: { workspaceSlug, feedbackId }, body: response });
	};

	return {
		write,
		respond: (observation, response) => {
			const feedbackId = observation.feedbackResponse?.feedbackId;
			if (hasText(feedbackId)) {
				write(feedbackId, response);
			}
		},
		// The cache lists mutations oldest first, so a later write to the same feedback wins.
		pendingResponses: new Map(pending.filter((entry) => entry !== undefined)),
	};
}
