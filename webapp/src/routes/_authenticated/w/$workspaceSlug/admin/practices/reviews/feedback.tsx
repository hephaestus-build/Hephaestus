import { useInfiniteQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { listReviewFeedbackInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { FeedbackListPage } from "@/components/admin/practice-reviews/FeedbackListPage";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	type FeedbackSearch,
	feedbackQuery,
	feedbackSearchSchema,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { useReviewScopeSources } from "@/hooks/use-review-scope-sources";
import { useScopeRecordFilter } from "@/hooks/use-scope-record-filter";
import { useSearchState } from "@/lib/search-params";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { offsetPageParams } from "@/runtime/tanstack-query/list-pages";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/feedback",
)({
	validateSearch: feedbackSearchSchema,
	head: practiceReviewsHead("Feedback"),
	component: FeedbackListRoute,
});

function FeedbackListRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const updateSearch = (patch: Partial<FeedbackSearch>) => {
		void setSearch((previous) => ({ ...previous, ...patch }), { replace: true });
	};

	const listOptions = listReviewFeedbackInfiniteOptions({
		path: { workspaceSlug },
		query: feedbackQuery(search, REVIEW_PAGE_SIZE),
	});
	const feedbackQueryResult = useInfiniteQuery({
		...listOptions,
		...offsetPageParams,
	});
	useQueryReadRefusal(listOptions.queryKey, feedbackQueryResult.isError, feedbackQueryResult.error);
	const { groups, practices } = useReviewScopeSources(workspaceSlug);

	const record = useScopeRecordFilter(workspaceSlug, search);

	return (
		<FeedbackListPage
			record={record}
			search={search}
			onSearchChange={updateSearch}
			feedback={pagedListState(feedbackQueryResult, (feedback) => feedback.id)}
			groups={groups}
			practices={practices}
		/>
	);
}
