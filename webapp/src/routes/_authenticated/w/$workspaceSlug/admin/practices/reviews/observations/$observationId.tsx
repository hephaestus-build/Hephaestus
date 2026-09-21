import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	getPracticeReviewObservationOptions,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import { ObservationDetailPage } from "@/components/admin/practice-reviews/ObservationDetailPage";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/observations/$observationId",
)({
	head: workspaceAdminHead("Observation"),
	component: ObservationDetailRoute,
});

function ObservationDetailRoute() {
	const { workspaceSlug, observationId } = Route.useParams();
	const search = Route.useSearch();

	const observationQueryResult = useQuery({
		...getPracticeReviewObservationOptions({ path: { workspaceSlug, observationId } }),
	});
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });

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
		/>
	);
}
