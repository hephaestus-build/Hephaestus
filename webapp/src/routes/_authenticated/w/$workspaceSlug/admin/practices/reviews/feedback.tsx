import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	listGroupsOptions,
	listPracticeReviewFeedbackOptions,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import { FeedbackListPage } from "@/components/admin/practice-reviews/FeedbackListPage";
import { practiceFacetOptions } from "@/components/admin/practice-reviews/ObservationFilters";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	type FeedbackSearch,
	feedbackQuery,
	feedbackSearchSchema,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { useClampedPage } from "@/hooks/use-clamped-page";
import { useReviewPeople } from "@/hooks/use-review-people";
import { pageParam, useSearchState } from "@/lib/search-params";

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
		// Any change but a page's own sends the reader back to page one.
		void setSearch((previous) => ({ ...previous, ...patch, page: pageParam(patch.page) }), {
			replace: true,
		});
	};

	const feedbackQueryResult = useQuery({
		...listPracticeReviewFeedbackOptions({
			path: { workspaceSlug },
			query: feedbackQuery(search, REVIEW_PAGE_SIZE),
		}),
	});
	// The groups only describe each practice option; there is no group facet here.
	const groupsQuery = useQuery({ ...listGroupsOptions({ path: { workspaceSlug } }) });
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });
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
			practices={{
				options: practiceFacetOptions(practicesQuery.data, groupsQuery.data),
				isLoading: practicesQuery.isLoading,
				isError: practicesQuery.isError,
			}}
			people={people}
		/>
	);
}
