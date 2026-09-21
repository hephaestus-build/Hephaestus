import { useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";

import { listPracticeReviewsOptions } from "@/api/@tanstack/react-query.gen";
import {
	ACTIVE_REVIEW_POLL_MS,
	REVIEW_PAGE_SIZE,
	type RunsSearch,
	runsQuery,
	runsSearchSchema,
} from "@/components/admin/practice-reviews/review-search";
import { ReviewRunsPage } from "@/components/admin/practice-reviews/ReviewRunsPage";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { workspaceAdminHead } from "@/lib/page-title";
import { pageParam } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/admin/practices/reviews/")({
	validateSearch: runsSearchSchema,
	head: workspaceAdminHead("Practice reviews"),
	component: ReviewRunsRoute,
});

function ReviewRunsRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const updateSearch = (patch: Partial<RunsSearch>) => {
		void navigate({
			search: (previous) => {
				const next = { ...previous, ...patch };
				return { ...next, page: pageParam(next.page) };
			},
			replace: true,
		});
	};
	const reviewsQuery = useQuery({
		...listPracticeReviewsOptions({
			path: { workspaceSlug },
			query: runsQuery(search, REVIEW_PAGE_SIZE),
		}),
		// A queued or running review is re-asked for on a timer and the interval stops on its own once
		// every row on the page has reached a terminal status. The screen below is told nothing about
		// this: it renders whichever answer is current, so a row that changes under the reader looks
		// exactly like a row that arrived that way.
		refetchInterval: (result) =>
			result.state.data?.content?.some(
				(review) => review.status === "QUEUED" || review.status === "RUNNING",
			) === true
				? ACTIVE_REVIEW_POLL_MS
				: false,
	});

	useClampedPage(search.page, reviewsQuery.data?.page?.totalPages, (page) =>
		updateSearch({ page }),
	);

	return (
		<ReviewRunsPage
			workspaceSlug={workspaceSlug}
			search={search}
			onSearchChange={updateSearch}
			reviews={reviewsQuery.data}
			isLoading={reviewsQuery.isLoading}
			error={reviewsQuery.error}
			onRetry={() => {
				void reviewsQuery.refetch();
			}}
		/>
	);
}
