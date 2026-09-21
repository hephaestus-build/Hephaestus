import { useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";

import { listPracticeReviewFeedbackOptions } from "@/api/@tanstack/react-query.gen";
import { FeedbackListPage } from "@/components/admin/practice-reviews/FeedbackListPage";
import {
	type FeedbackSearch,
	feedbackQuery,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { useReviewPeople } from "@/hooks/use-review-people";
import { workspaceAdminHead } from "@/lib/page-title";
import { pageParam } from "@/lib/search-params";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/delivery/",
)({
	head: workspaceAdminHead("Feedback delivery"),
	component: FeedbackListRoute,
});

function FeedbackListRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const updateSearch = (patch: Partial<FeedbackSearch>) => {
		void navigate({
			search: (previous) => {
				const next = { ...previous, ...patch };
				return { ...next, page: pageParam(next.page) };
			},
			replace: true,
		});
	};

	const feedbackQueryResult = useQuery({
		...listPracticeReviewFeedbackOptions({
			path: { workspaceSlug },
			query: feedbackQuery(search, REVIEW_PAGE_SIZE),
		}),
	});
	const people = useReviewPeople(workspaceSlug);

	// Reconciles the page in the URL with the page the server actually has, so it belongs beside the
	// query rather than on the screen that only draws what it is handed.
	useClampedPage(search.page, feedbackQueryResult.data?.page?.totalPages, (page) =>
		updateSearch({ page }),
	);

	return (
		<FeedbackListPage
			workspaceSlug={workspaceSlug}
			search={search}
			onSearchChange={updateSearch}
			feedback={feedbackQueryResult.data}
			isLoading={feedbackQueryResult.isLoading}
			error={feedbackQueryResult.isError ? feedbackQueryResult.error : undefined}
			onRetry={() => {
				void feedbackQueryResult.refetch();
			}}
			people={people}
		/>
	);
}
