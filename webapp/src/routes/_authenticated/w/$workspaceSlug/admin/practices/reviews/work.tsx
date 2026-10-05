import { useInfiniteQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { listTracedArtifactsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	REVIEW_PAGE_SIZE,
	type WorkSearch,
	workQuery,
	workSearchSchema,
} from "@/components/admin/practice-reviews/review-search";
import { WorkListPage } from "@/components/admin/practice-reviews/WorkListPage";
import { useSearchState } from "@/lib/search-params";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { pagedModelParams } from "@/runtime/tanstack-query/spring-page";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/work",
)({
	validateSearch: workSearchSchema,
	head: practiceReviewsHead("Work"),
	component: WorkRoute,
});

function WorkRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const updateSearch = (patch: Partial<WorkSearch>) => {
		void setSearch((previous) => ({ ...previous, ...patch }), { replace: true });
	};
	const workQueryResult = useInfiniteQuery({
		...listTracedArtifactsInfiniteOptions({
			path: { workspaceSlug },
			query: workQuery(search, REVIEW_PAGE_SIZE),
		}),
		...pagedModelParams,
	});

	return (
		<WorkListPage
			search={search}
			onSearchChange={updateSearch}
			work={pagedListState(workQueryResult)}
		/>
	);
}
