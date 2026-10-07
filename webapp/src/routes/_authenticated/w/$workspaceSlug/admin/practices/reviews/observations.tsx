import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	listGroupsOptions,
	listPracticeReviewObservationsInfiniteOptions,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	groupFacetOptions,
	practiceFacetOptions,
} from "@/components/admin/practice-reviews/ObservationFilters";
import { ObservationsListPage } from "@/components/admin/practice-reviews/ObservationsListPage";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	type ObservationsSearch,
	observationsQuery,
	observationsSearchSchema,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { useReviewPeople } from "@/hooks/use-review-people";
import { useSearchState } from "@/lib/search-params";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { pagedModelParams } from "@/runtime/tanstack-query/spring-page";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/observations",
)({
	validateSearch: observationsSearchSchema,
	head: practiceReviewsHead("Observations"),
	component: ObservationsListRoute,
});

function ObservationsListRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const updateSearch = (patch: Partial<ObservationsSearch>) => {
		void setSearch((previous) => ({ ...previous, ...patch }), { replace: true });
	};

	const observationsQueryResult = useInfiniteQuery({
		...listPracticeReviewObservationsInfiniteOptions({
			path: { workspaceSlug },
			query: observationsQuery(search, REVIEW_PAGE_SIZE),
		}),
		...pagedModelParams,
	});
	const groupsQuery = useQuery({ ...listGroupsOptions({ path: { workspaceSlug } }) });
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });
	const people = useReviewPeople(workspaceSlug);

	return (
		<ObservationsListPage
			search={search}
			onSearchChange={updateSearch}
			observations={pagedListState(observationsQueryResult, (observation) => observation.id)}
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
