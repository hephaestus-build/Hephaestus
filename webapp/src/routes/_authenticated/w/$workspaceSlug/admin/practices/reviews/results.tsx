import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import {
	getReviewOverviewOptions,
	listReviewResultsInfiniteOptions,
} from "@/api/@tanstack/react-query.gen";
import { PracticeResultsSummary } from "@/components/admin/practice-reviews/PracticeResultsSummary";
import { ResultsListPage } from "@/components/admin/practice-reviews/ResultsListPage";
import { practiceReviewsHead } from "@/components/admin/practice-reviews/review-levels";
import {
	dateWindowQuery,
	type ResultsSearch,
	resultsQuery,
	resultsSearchSchema,
	onePracticeQuery,
	REVIEW_PAGE_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { dateRangeLabel } from "@/components/common/DateRangeFacet";
import { panelState } from "@/components/common/panel-state";
import { useReviewScopeSources } from "@/hooks/use-review-scope-sources";
import { useScopeRecordFilter } from "@/hooks/use-scope-record-filter";
import { toDateRange } from "@/lib/date-range-search";
import { browserTimeZone } from "@/lib/dates";
import { useSearchState } from "@/lib/search-params";
import { pagedListState } from "@/runtime/tanstack-query/infinite-list";
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";
import { springPageParams } from "@/runtime/tanstack-query/spring-page";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/results",
)({
	validateSearch: resultsSearchSchema,
	head: practiceReviewsHead("Observations"),
	component: ResultsListRoute,
});

function ResultsListRoute() {
	const { workspaceSlug } = Route.useParams();
	const search = Route.useSearch();
	const setSearch = useSearchState();
	const updateSearch = (patch: Partial<ResultsSearch>) => {
		void setSearch((previous) => ({ ...previous, ...patch }), { replace: true });
	};

	const listOptions = listReviewResultsInfiniteOptions({
		path: { workspaceSlug },
		query: resultsQuery(search, REVIEW_PAGE_SIZE),
	});
	const resultsQueryResult = useInfiniteQuery({
		...listOptions,
		...springPageParams,
	});
	useQueryReadRefusal(listOptions.queryKey, resultsQueryResult.isError, resultsQueryResult.error);
	const { groups, practices, people } = useReviewScopeSources(workspaceSlug);
	const record = useScopeRecordFilter(workspaceSlug, search);
	const summaryPractice = onePracticeQuery(search);

	return (
		<ResultsListPage
			search={search}
			onSearchChange={updateSearch}
			results={pagedListState(resultsQueryResult, (result) => result.id)}
			groups={groups}
			practices={practices}
			people={people}
			record={record}
			summary={
				summaryPractice === undefined ? undefined : (
					<PracticeSummaryRead
						workspaceSlug={workspaceSlug}
						practiceSlug={summaryPractice}
						practiceName={
							practices.options.find((option) => option.value === summaryPractice)?.label
						}
						search={search}
					/>
				)
			}
		/>
	);
}

function PracticeSummaryRead({
	workspaceSlug,
	practiceSlug,
	practiceName,
	search,
}: {
	workspaceSlug: string;
	practiceSlug: string;
	practiceName: string | undefined;
	search: ResultsSearch;
}) {
	const window = dateWindowQuery(search);
	const query = useQuery({
		...getReviewOverviewOptions({
			path: { workspaceSlug },
			query: {
				...(window.from === undefined ? { allTime: true } : { from: window.from }),
				to: window.to,
				zone: browserTimeZone(),
			},
		}),
		placeholderData: keepSameSubject({ workspaceSlug }),
	});
	const range = toDateRange(search);
	return (
		<PracticeResultsSummary
			workspaceSlug={workspaceSlug}
			practiceSlug={practiceSlug}
			practiceName={practiceName}
			rangeLabel={(range && dateRangeLabel(range)) ?? "All time"}
			counts={panelState(query, (overview) => ({
				status: "ready" as const,
				stale: query.isPlaceholderData,
				counts: overview.practices.find((counts) => counts.practiceSlug === practiceSlug),
			}))}
		/>
	);
}
