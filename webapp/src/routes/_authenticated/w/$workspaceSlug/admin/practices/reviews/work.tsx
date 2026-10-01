import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { listTracedArtifactsOptions } from "@/api/@tanstack/react-query.gen";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	REVIEW_PAGE_SIZE,
	type WorkSearch,
	workQuery,
	workSearchSchema,
} from "@/components/admin/practice-reviews/review-search";
import { WorkListPage } from "@/components/admin/practice-reviews/WorkListPage";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { pageParam, useSearchState } from "@/lib/search-params";

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
		// Any change but a page's own sends the reader back to page one.
		void setSearch((previous) => ({ ...previous, ...patch, page: pageParam(patch.page) }), {
			replace: true,
		});
	};
	const workQueryResult = useQuery({
		...listTracedArtifactsOptions({
			path: { workspaceSlug },
			query: workQuery(search, REVIEW_PAGE_SIZE),
		}),
	});

	useClampedPage(search.page, workQueryResult.data?.page?.totalPages, (page) =>
		updateSearch({ page }),
	);

	return (
		<WorkListPage
			workspaceSlug={workspaceSlug}
			search={search}
			onSearchChange={updateSearch}
			work={workQueryResult.data}
			isLoading={workQueryResult.isLoading}
			error={workQueryResult.error}
			onRetry={() => {
				void workQueryResult.refetch();
			}}
		/>
	);
}
