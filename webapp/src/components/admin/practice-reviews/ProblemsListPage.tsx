import type { ReviewProblem } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";
import { ReviewListRefresh } from "./ReviewListRefresh";

import {
	clearedProblemFacets,
	clearedProblemFilters,
	hasProblemFilter,
	ProblemFilters,
} from "./ProblemFilters";
import { ProblemRows, type ProblemRowsState } from "./ProblemRows";
import type { PracticeFacetOption } from "./ResultFilters";
import { clearedScope } from "./review-scope";
import type { ProblemsSearch } from "./review-search";
import { loadingMoreRows, ReviewListEnd } from "./ReviewListEnd";
import type { ReviewPeople } from "./ReviewPersonFacet";
import type { ScopeRecordFilter } from "./ReviewScopeFilters";
import { ReviewTabIntro } from "./ReviewTabIntro";

export interface ProblemsListPageProps {
	search: ProblemsSearch;
	onSearchChange: (patch: Partial<ProblemsSearch>) => void;
	problems: PagedListState<ReviewProblem>;
	groups: FacetSource;
	practices: FacetSource<PracticeFacetOption>;
	people: ReviewPeople;
	record?: ScopeRecordFilter;
}

function problemsState(
	rows: ReviewProblem[],
	onClearFilters: (() => void) | undefined,
): ProblemRowsState {
	if (rows.length > 0) {
		return { status: "ready", problems: rows };
	}
	return onClearFilters
		? { status: "empty", filtered: true, onClearFilters }
		: { status: "empty", filtered: false };
}

export function ProblemsListPage({
	search,
	onSearchChange,
	problems,
	groups,
	practices,
	people,
	record,
}: ProblemsListPageProps) {
	const rows = problems.status === "ready" ? problems.rows : [];
	const filteredDeveloper = search.developerId === undefined ? undefined : rows[0]?.person;
	const reset = () => onSearchChange(clearedProblemFilters());

	return (
		<section aria-label="Practice review problems" className="space-y-4">
			<ReviewTabIntro tab="problems" />
			<ProblemFilters
				search={search}
				onPatch={onSearchChange}
				onReset={() => onSearchChange(clearedProblemFacets())}
				onClearScope={() => onSearchChange(clearedScope())}
				groups={groups}
				practices={practices}
				people={people}
				total={problems.status === "ready" ? problems.total : undefined}
				subjectName={
					filteredDeveloper?.displayName ?? filteredDeveloper?.providerLogin ?? undefined
				}
				record={record}
			/>
			{problems.status === "error" && (
				<ProblemRows
					named={people.named}
					state={{ status: "error", error: problems.error, onRetry: problems.onRetry }}
				/>
			)}
			{problems.status === "loading" && (
				<ProblemRows named={people.named} state={{ status: "loading" }} />
			)}
			{problems.status === "ready" && (
				<>
					<ReviewListRefresh failure={problems.refreshFailure} noun="problems" />
					<ProblemRows
						named={people.named}
						state={problemsState(rows, hasProblemFilter(search) ? reset : undefined)}
						loadingMore={loadingMoreRows(problems)}
					/>
					{rows.length > 0 && <ReviewListEnd {...problems} noun="problems" />}
				</>
			)}
		</section>
	);
}
