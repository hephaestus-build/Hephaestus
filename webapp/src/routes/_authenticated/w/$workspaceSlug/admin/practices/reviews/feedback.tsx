import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	listGroupsOptions,
	listPracticeReviewFeedbackInfiniteOptions,
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
import { useReviewPeople } from "@/hooks/use-review-people";
import { useSearchState } from "@/lib/search-params";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { pagedModelParams } from "@/runtime/tanstack-query/spring-page";

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

	const feedbackQueryResult = useInfiniteQuery({
		...listPracticeReviewFeedbackInfiniteOptions({
			path: { workspaceSlug },
			query: feedbackQuery(search, REVIEW_PAGE_SIZE),
		}),
		...pagedModelParams,
	});
	// The groups only describe each practice option; there is no group facet here.
	const groupsQuery = useQuery({ ...listGroupsOptions({ path: { workspaceSlug } }) });
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });
	const people = useReviewPeople(workspaceSlug);

	return (
		<FeedbackListPage
			search={search}
			onSearchChange={updateSearch}
			feedback={pagedListState(feedbackQueryResult)}
			practices={{
				options: practiceFacetOptions(practicesQuery.data, groupsQuery.data),
				isLoading: practicesQuery.isLoading,
				isError: practicesQuery.isError,
			}}
			people={people}
		/>
	);
}
