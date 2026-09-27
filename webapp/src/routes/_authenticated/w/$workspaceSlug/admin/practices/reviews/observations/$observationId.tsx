import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";

import {
	getPracticeReviewObservationOptions,
	getPracticeReviewObservationQueryKey,
	listPracticesOptions,
	updatePracticeReviewObservationValidityMutation,
} from "@/api/@tanstack/react-query.gen";
import type { ObservationInvalidation } from "@/api/types.gen";
import { ObservationDetailPage } from "@/components/admin/practice-reviews/ObservationDetailPage";
import { isRecord } from "@/lib/is-record";
import { workspaceAdminHead } from "@/lib/page-title";
import { problemDetailOf } from "@/lib/problem-detail";
import { queryOperationId } from "@/lib/query-operation-id";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/observations/$observationId",
)({
	head: workspaceAdminHead("Observation"),
	component: ObservationDetailRoute,
});

/** How often to re-read a correction the server is still settling; a final outcome is not re-read. */
const PROVIDER_COPY_POLL_MS: Partial<Record<ObservationInvalidation["providerCopy"], number>> = {
	PENDING: 10_000,
	UNRESOLVED: 60_000,
};

/** Every read in this workspace that counts, lists or delivers observations, so none keeps the old answer. */
const READS_OF_OBSERVATION_VALIDITY: ReadonlySet<string> = new Set([
	"listPracticeReviewObservations",
	"listPracticeReviews",
	"listPracticeReviewFeedback",
	"getPracticeReviewFeedback",
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

function ObservationDetailRoute() {
	const { workspaceSlug, observationId } = Route.useParams();
	const search = Route.useSearch();
	const queryClient = useQueryClient();

	const observationQueryResult = useQuery({
		...getPracticeReviewObservationOptions({ path: { workspaceSlug, observationId } }),
		refetchInterval: (query) => {
			const providerCopy = query.state.data?.invalidations.at(0)?.providerCopy;
			return (providerCopy && PROVIDER_COPY_POLL_MS[providerCopy]) ?? false;
		},
	});
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });
	const validity = useMutation({
		...updatePracticeReviewObservationValidityMutation(),
		onSuccess: (updated, variables) => {
			queryClient.setQueryData(
				getPracticeReviewObservationQueryKey({ path: { workspaceSlug, observationId } }),
				updated,
			);
			void queryClient.invalidateQueries({
				predicate: ({ queryKey }) => {
					const id = queryOperationId(queryKey);
					const [key] = queryKey;
					return (
						id !== undefined &&
						READS_OF_OBSERVATION_VALIDITY.has(id) &&
						isRecord(key) &&
						isRecord(key.path) &&
						key.path.workspaceSlug === workspaceSlug
					);
				},
			});
			toast.success(
				variables.body.valid ? "Observation restored" : "Observation marked as incorrect",
			);
		},
		onError: (error) => {
			void observationQueryResult.refetch();
			toast.error("Couldn't change this observation", { description: problemDetailOf(error) });
		},
	});

	return (
		<ObservationDetailPage
			workspaceSlug={workspaceSlug}
			search={search}
			observation={observationQueryResult.data}
			isLoading={observationQueryResult.isLoading}
			error={observationQueryResult.isError ? observationQueryResult.error : undefined}
			onRetry={() => {
				void observationQueryResult.refetch();
			}}
			practices={practicesQuery.data}
			isChangingValidity={validity.isPending}
			onChangeValidity={async (valid, reason) =>
				validity.mutateAsync({ path: { workspaceSlug, observationId }, body: { valid, reason } })
			}
		/>
	);
}
