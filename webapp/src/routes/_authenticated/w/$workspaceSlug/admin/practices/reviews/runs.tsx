import { useInfiniteQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { listPracticeReviewsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	ACTIVE_REVIEW_POLL_MS,
	REVIEW_PAGE_SIZE,
	type RunsSearch,
	runsQuery,
	runsSearchSchema,
} from "@/components/admin/practice-reviews/review-search";
import { ReviewRunsPage } from "@/components/admin/practice-reviews/ReviewRunsPage";
import { useSearchState } from "@/lib/search-params";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { loadedPages, pagedModelParams } from "@/runtime/tanstack-query/spring-page";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/runs",
)({
	validateSearch: runsSearchSchema,
	head: practiceReviewsHead("Reviews"),
	component: ReviewRunsRoute,
});

function ReviewRunsRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const updateSearch = (patch: Partial<RunsSearch>) => {
		void setSearch((previous) => ({ ...previous, ...patch }), { replace: true });
	};
	const reviewsQuery = useInfiniteQuery({
		...listPracticeReviewsInfiniteOptions({
			path: { workspaceSlug },
			query: runsQuery(search, REVIEW_PAGE_SIZE),
		}),
		...pagedModelParams,
		// A queued or running review is re-asked for on a timer and the interval stops on its own once
		// every loaded row has reached a terminal status. The screen below is told nothing about
		// this: it renders whichever answer is current, so a row that changes under the reader looks
		// exactly like a row that arrived that way.
		refetchInterval: (result) =>
			loadedPages(result.state.data).some(
				(page) =>
					page.content?.some(
						(review) => review.status === "QUEUED" || review.status === "RUNNING",
					) === true,
			)
				? ACTIVE_REVIEW_POLL_MS
				: false,
	});

	return (
		<ReviewRunsPage
			search={search}
			onSearchChange={updateSearch}
			reviews={pagedListState(reviewsQuery)}
		/>
	);
}
