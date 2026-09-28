import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	getPracticeReviewObservationOptions,
	getPracticeReviewObservationQueryKey,
	updatePracticeReviewObservationValidityMutation,
} from "@/api/@tanstack/react-query.gen";
import type {
	GetPracticeReviewObservationResponse,
	ObservationInvalidation,
} from "@/api/types.gen";
import { PRACTICE_REVIEW_READS } from "@/components/admin/practice-reviews/review-search";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { problemDetailOf } from "@/lib/problem-detail";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

/** How often to re-read a correction the server is still settling; a final outcome is not re-read. */
const PROVIDER_COPY_POLL_MS: Partial<Record<ObservationInvalidation["providerCopy"], number>> = {
	PENDING: 10_000,
	UNRESOLVED: 60_000,
};

/**
 * Every read an observation's validity moves: the admin's, and the developer's own surfaces that
 * count it or deliver feedback about it.
 */
const READS_OF_OBSERVATION_VALIDITY: ReadonlySet<string> = new Set([
	...PRACTICE_REVIEW_READS,
	"getArtifactTrace",
	"listObservations",
	"getObservation",
	"getObservationsForPullRequest",
	"getSummary",
	"listPracticeStandings",
	"listPracticeGroupStandings",
	"getPracticeGroupTrend",
	"listPracticeGroupReviewRuns",
	"getPracticeProfileOverview",
	"getInAppFeedback",
	"getFeedbackResolutionCounts",
]);

interface ObservationController {
	observation: PanelState<{ observation: GetPracticeReviewObservationResponse }>;
	onChangeValidity: (valid: boolean, reason: string) => Promise<unknown>;
	isChangingValidity: boolean;
}

/** One observation as its level reads it, and the one decision an admin can take on it. */
export function useObservationController(
	workspaceSlug: string,
	observationId: string,
): ObservationController {
	const queryClient = useQueryClient();
	const observationQuery = useQuery({
		...getPracticeReviewObservationOptions({ path: { workspaceSlug, observationId } }),
		refetchInterval: (query) => {
			const providerCopy = query.state.data?.invalidations.at(0)?.providerCopy;
			return (providerCopy && PROVIDER_COPY_POLL_MS[providerCopy]) ?? false;
		},
	});
	const validity = useMutation({
		...updatePracticeReviewObservationValidityMutation(),
		onSuccess: (updated, variables) => {
			queryClient.setQueryData(
				getPracticeReviewObservationQueryKey({ path: { workspaceSlug, observationId } }),
				updated,
			);
			void invalidateWorkspaceReads(queryClient, workspaceSlug, READS_OF_OBSERVATION_VALIDITY);
			toast.success(
				variables.body.valid ? "Observation restored" : "Observation marked as incorrect",
			);
		},
		onError: (error) => {
			void observationQuery.refetch();
			toast.error("Couldn't change this observation", { description: problemDetailOf(error) });
		},
	});

	return {
		observation: panelState(observationQuery, (observation) => ({
			status: "ready" as const,
			observation,
		})),
		isChangingValidity: validity.isPending,
		onChangeValidity: async (valid, reason) =>
			validity.mutateAsync({ path: { workspaceSlug, observationId }, body: { valid, reason } }),
	};
}
