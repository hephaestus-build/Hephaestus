import { useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";

import {
	listGroupsOptions,
	listPracticeReviewObservationsOptions,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	groupFacetOptions,
	practiceFacetOptions,
} from "@/components/admin/practice-reviews/ObservationFilters";
import { ObservationsListPage } from "@/components/admin/practice-reviews/ObservationsListPage";
import {
	type ObservationsSearch,
	observationsQuery,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { useReviewPeople } from "@/hooks/use-review-people";
import { workspaceAdminHead } from "@/lib/page-title";
import { pageParam } from "@/lib/search-params";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/observations/",
)({
	head: workspaceAdminHead("Observations"),
	component: ObservationsListRoute,
});

function ObservationsListRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const updateSearch = (patch: Partial<ObservationsSearch>) => {
		void navigate({
			search: (previous) => {
				const next = { ...previous, ...patch };
				return { ...next, page: pageParam(next.page) };
			},
			replace: true,
		});
	};

	const observationsQueryResult = useQuery({
		...listPracticeReviewObservationsOptions({
			path: { workspaceSlug },
			query: observationsQuery(search, REVIEW_PAGE_SIZE),
		}),
	});
	const groupsQuery = useQuery({ ...listGroupsOptions({ path: { workspaceSlug } }) });
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });
	const people = useReviewPeople(workspaceSlug);

	useClampedPage(search.page, observationsQueryResult.data?.page?.totalPages, (page) =>
		updateSearch({ page }),
	);

	return (
		<ObservationsListPage
			workspaceSlug={workspaceSlug}
			search={search}
			onSearchChange={updateSearch}
			observations={observationsQueryResult.data}
			isLoading={observationsQueryResult.isLoading}
			error={observationsQueryResult.isError ? observationsQueryResult.error : undefined}
			onRetry={() => {
				void observationsQueryResult.refetch();
			}}
			groups={{
				options: groupFacetOptions(groupsQuery.data),
				isLoading: groupsQuery.isLoading,
				isError: groupsQuery.isError,
			}}
			practices={{
				options: practiceFacetOptions(practicesQuery.data, groupsQuery.data),
				isLoading: practicesQuery.isLoading,
				isError: practicesQuery.isError,
			}}
			practiceRecords={practicesQuery.data}
			people={people}
		/>
	);
}
