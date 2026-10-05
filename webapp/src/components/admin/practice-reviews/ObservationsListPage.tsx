import type { Practice, ReviewObservation } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";

import {
	clearedObservationFilters,
	hasObservationFilter,
	ObservationFilters,
} from "./ObservationFilters";
import { ObservationResults, type ObservationResultsState } from "./ObservationResults";
import type { ObservationsSearch } from "./review-search";
import { ReviewListEnd } from "./ReviewListEnd";
import type { ReviewPeople } from "./ReviewPersonFacet";

export interface ObservationsListPageProps {
	search: ObservationsSearch;
	onSearchChange: (patch: Partial<ObservationsSearch>) => void;
	/** The observations the current `search` selects, as far as they are loaded. */
	observations: PagedListState<ReviewObservation>;
	groups: FacetSource;
	practices: FacetSource;
	/**
	 * The practice records themselves, which the rows' practice links show as a hover card. Distinct
	 * from `practices` above: that is the facet's option list, a label per slug, and it carries none
	 * of the prose the card shows.
	 */
	practiceRecords?: Practice[];
	people: ReviewPeople;
}

function resultsState(
	rows: ReviewObservation[],
	onClearFilters: (() => void) | undefined,
): ObservationResultsState {
	if (rows.length > 0) {
		return { status: "ready", observations: rows };
	}
	return onClearFilters
		? { status: "empty", filtered: true, onClearFilters }
		: { status: "empty", filtered: false };
}

export function ObservationsListPage({
	search,
	onSearchChange,
	observations,
	groups,
	practices,
	practiceRecords,
	people,
}: ObservationsListPageProps) {
	const rows = observations.status === "ready" ? observations.rows : [];
	// Guarded on the filter being set, because that is the only condition under which the first row
	// names the filtered person — unfiltered, row zero is whoever happens to sort first, and the facet
	// would put a stranger's name on somebody else's id.
	const filteredSubject = search.subjectUserId == null ? undefined : rows[0]?.subject;
	const hasFilter = hasObservationFilter(search);
	const reset = () => onSearchChange(clearedObservationFilters());

	return (
		<section aria-label="Practice review observations" className="space-y-4">
			<ObservationFilters
				search={search}
				onPatch={onSearchChange}
				onReset={reset}
				groups={groups}
				practices={practices}
				people={people}
				total={observations.status === "ready" ? observations.total : undefined}
				scopedWork={rows[0]?.reviewedWork}
				subjectName={filteredSubject?.name ?? filteredSubject?.login}
			/>
			{observations.status === "error" && (
				<QueryErrorAlert
					error={observations.error}
					title="We could not load observations"
					onRetry={observations.onRetry}
				/>
			)}
			{observations.status === "loading" && <ObservationResults state={{ status: "loading" }} />}
			{observations.status === "ready" && (
				<>
					<ObservationResults
						practices={practiceRecords}
						state={resultsState(rows, hasFilter ? reset : undefined)}
					/>
					<ReviewListEnd {...observations} noun="observations" />
				</>
			)}
		</section>
	);
}
