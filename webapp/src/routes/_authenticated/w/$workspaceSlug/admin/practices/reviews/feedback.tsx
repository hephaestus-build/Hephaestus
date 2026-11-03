import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	countReviewFeedbackByLocationOptions,
	listReviewFeedbackInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import { FeedbackListPage } from "@/components/admin/practice-reviews/FeedbackListPage";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	type FeedbackSearch,
	feedbackQuery,
	feedbackSearchSchema,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { panelState } from "@/components/common/panel-state";
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
	const { groups, practices, people } = useReviewScopeSources(workspaceSlug);
	const developerCountsQuery = useQuery({
		...countReviewFeedbackByLocationOptions({
			path: { workspaceSlug },
			query: { developerId: search.developerId },
		}),
		enabled: search.developerId !== undefined,
	});

	const record = useScopeRecordFilter(workspaceSlug, search);

	return (
		<FeedbackListPage
			record={record}
			workspaceSlug={workspaceSlug}
			search={search}
			onSearchChange={updateSearch}
			feedback={pagedListState(feedbackQueryResult, (feedback) => feedback.id)}
			groups={groups}
			practices={practices}
			people={people}
			developerCounts={panelState(developerCountsQuery, (counts) => ({
				status: "ready" as const,
				counts,
			}))}
		/>
	);
}
