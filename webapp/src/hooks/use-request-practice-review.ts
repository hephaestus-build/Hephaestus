import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	getArtifactTraceQueryKey,
	getPracticeProfileOverviewQueryKey,
	requestPracticeReviewMutation,
} from "@/api/@tanstack/react-query.gen";
import { problemDetailOf } from "@/lib/problem-detail";

/**
 * "Review this now". A started review refreshes the asked-about work's review activity and the
 * practice profile's latest-review chip; a refusal is the caller's to show, from `data`.
 */
export function useRequestPracticeReview() {
	const queryClient = useQueryClient();
	return useMutation({
		...requestPracticeReviewMutation(),
		onSuccess: (outcome, { path, body }) => {
			if (outcome.status !== "SUBMITTED") {
				return;
			}
			void queryClient.invalidateQueries({
				queryKey: getArtifactTraceQueryKey({
					path: { ...path, artifactKind: body.artifactKind, artifactId: body.artifactId },
				}),
			});
			void queryClient.invalidateQueries({
				queryKey: getPracticeProfileOverviewQueryKey({ path }),
			});
			toast.success("Review started");
		},
		onError: (error) =>
			toast.error("Couldn't ask for a review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});
}
