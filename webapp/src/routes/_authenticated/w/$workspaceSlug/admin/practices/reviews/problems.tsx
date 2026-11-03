import { useInfiniteQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { listReviewProblemsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { ProblemsListPage } from "@/components/admin/practice-reviews/ProblemsListPage";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	type ProblemsSearch,
	problemsQuery,
	problemsSearchSchema,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { useReviewScopeSources } from "@/hooks/use-review-scope-sources";
import { useScopeRecordFilter } from "@/hooks/use-scope-record-filter";
import { useSearchState } from "@/lib/search-params";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { offsetPageParams } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/problems",
)({
	validateSearch: problemsSearchSchema,
	head: practiceReviewsHead("Problems"),
	component: ProblemsListRoute,
});

function ProblemsListRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const updateSearch = (patch: Partial<ProblemsSearch>) => {
		void setSearch((previous) => ({ ...previous, ...patch }), { replace: true });
	};

	const listOptions = listReviewProblemsInfiniteOptions({
		path: { workspaceSlug },
		query: problemsQuery(search, REVIEW_PAGE_SIZE),
	});
	const problemsQueryResult = useInfiniteQuery({
		...listOptions,
		...offsetPageParams,
	});
	useQueryReadRefusal(listOptions.queryKey, problemsQueryResult.isError, problemsQueryResult.error);
	const { groups, practices, people } = useReviewScopeSources(workspaceSlug);
	const record = useScopeRecordFilter(workspaceSlug, search);

	return (
		<ProblemsListPage
			search={search}
			onSearchChange={updateSearch}
			problems={pagedListState(problemsQueryResult, (problem) => problem.id)}
			groups={groups}
			practices={practices}
			people={people}
			record={record}
		/>
	);
}
