import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import {
	getArtifactTraceQueryKey,
	getPracticeProfileOverviewQueryKey,
	requestPracticeReviewMutation,
} from "@/api/@tanstack/react-query.gen";
import type { CreateReviewRequest, ReviewRequestOutcome } from "@/api/types.gen";
import { problemDetailOf } from "@/lib/problem-detail";

/** Why an ask about one piece of work started nothing, for the level that shows that work. */
export interface ReviewRefusal {
	work: CreateReviewRequest;
	outcome: ReviewRequestOutcome;
}

interface RequestPracticeReviewOptions {
	/**
	 * Whether a level on screen shows this work, and so says its refusal inline; asked when the
	 * answer lands, since the reader may have moved on by then. Anywhere else a refusal is a toast.
	 */
	showsInline?: (work: CreateReviewRequest) => boolean;
}

/**
 * "Review this now", and the one home of what its answer shows. A started review refreshes the
 * asked-about work's trace and the practice profile's latest-review chip; a refusal is `refusal`
 * where a level shows the work, and a toast carrying the server's sentence otherwise.
 *
 * Both are handled here rather than in callbacks passed to `mutate`, which TanStack Query calls only
 * for the latest ask and only while the caller is mounted: a refusal of one piece of work answered
 * after a second was asked for would say nothing at all.
 */
export function useRequestPracticeReview(
	workspaceSlug: string,
	{ showsInline }: RequestPracticeReviewOptions = {},
) {
	const queryClient = useQueryClient();
	const [refusal, setRefusal] = useState<ReviewRefusal>();
	// A level that closed before the answer landed can no longer say it.
	const mounted = useRef(false);
	useEffect(() => {
		mounted.current = true;
		return () => {
			mounted.current = false;
		};
	}, []);
	const mutation = useMutation({
		...requestPracticeReviewMutation(),
		onSuccess: (outcome, { path, body }) => {
			if (outcome.status === "REFUSED") {
				if (mounted.current && showsInline?.(body) === true) {
					setRefusal({ work: body, outcome });
				} else {
					toast.warning("No review was started", { description: outcome.reasonDescription });
				}
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
			toast.error("We could not request the review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});
	return {
		ask: (work: CreateReviewRequest) => {
			// A new ask replaces the answer to the last one, so an alert never outlives its refusal.
			setRefusal(undefined);
			mutation.mutate({ path: { workspaceSlug }, body: work });
		},
		/** The work the latest ask is about, while it is in flight. */
		asking: mutation.isPending ? mutation.variables.body : undefined,
		refusal,
		/** Forgets the refusal, for a surface whose level moved on to something else. */
		forgetRefusal: () => setRefusal(undefined),
	};
}
