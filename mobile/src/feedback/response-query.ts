import {
	partialMatchKey,
	type QueryClient,
	type QueryKey,
	queryOptions,
} from "@tanstack/react-query";

import {
	getFeedbackResponseQueryKey,
	getObservationQueryKey,
	listPracticeGroupReviewRunsInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { getFeedbackResponse } from "@/api/sdk.gen";

/**
 * The developer's answer to one piece of feedback, or null while there is none. The server says "none"
 * with a 204, which the status decides: a 204 has no Content-Type, so the generated client returns the
 * response body as its data, and expo/fetch hands over a body stream even for an empty response. The
 * cache keeps null because it cannot store undefined.
 */
export function feedbackResponseOptions(
	options: Parameters<typeof getFeedbackResponseQueryKey>[0],
) {
	return queryOptions({
		queryKey: getFeedbackResponseQueryKey(options),
		queryFn: async ({ signal }) => {
			const { data, response } = await getFeedbackResponse({
				...options,
				signal,
				throwOnError: true,
			});
			return (response.status === 204 ? undefined : data) ?? null;
		},
	});
}

/**
 * Every read that carries the developer's answer inside it, in one workspace: each observation and each
 * page of each group's review runs. The generated keys name the operations; narrowing their path to the
 * workspace matches whichever observation, group or practice a cached read was for.
 */
function answerCarriers(workspaceSlug: string): QueryKey[] {
	return [
		getObservationQueryKey({ path: { workspaceSlug, observationId: "" } }),
		listPracticeGroupReviewRunsInfiniteQueryKey({ path: { workspaceSlug, groupSlug: "" } }),
	].map(([key]) => [{ ...key, path: { workspaceSlug } }]);
}

/**
 * Once saving or taking back an answer has settled, whichever way, the reads that show it are read
 * again: those on screen now, the rest when they are next shown. The in-app feedback list is never among
 * them, because reading it delivers feedback.
 */
export async function refreshAnswerCarriers(queryClient: QueryClient, workspaceSlug: string) {
	const carriers = answerCarriers(workspaceSlug);
	await queryClient.invalidateQueries({
		predicate: (query) => carriers.some((carrier) => partialMatchKey(query.queryKey, carrier)),
	});
}
