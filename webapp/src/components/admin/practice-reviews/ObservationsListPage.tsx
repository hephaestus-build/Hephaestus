import { Link } from "@tanstack/react-router";

import type {
	ListPracticeReviewObservationsResponse,
	Practice,
	ReviewObservation,
} from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TablePagination } from "@/components/common/TablePagination";

import {
	clearedObservationFilters,
	hasObservationFilter,
	ObservationFilters,
} from "./ObservationFilters";
import { ObservationResults, type ObservationResultsState } from "./ObservationResults";
import type { ObservationsSearch } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";

export interface ObservationsListPageProps {
	workspaceSlug: string;
	search: ObservationsSearch;
	onSearchChange: (patch: Partial<ObservationsSearch>) => void;
	/** The page of observations the current `search` selects, or `undefined` while it is unknown. */
	observations: ListPracticeReviewObservationsResponse | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry?: () => void;
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
	isLoading: boolean,
	rows: ReviewObservation[],
	onClearFilters: (() => void) | undefined,
): ObservationResultsState {
	if (isLoading) {
		return { status: "loading" };
	}
	if (rows.length > 0) {
		return { status: "ready", observations: rows };
	}
	return onClearFilters
		? { status: "empty", filtered: true, onClearFilters }
		: { status: "empty", filtered: false };
}

export function ObservationsListPage({
	workspaceSlug,
	search,
	onSearchChange,
	observations,
	isLoading,
	error,
	onRetry,
	groups,
	practices,
	practiceRecords,
	people,
}: ObservationsListPageProps) {
	const rows = observations?.content ?? [];
	// Guarded on the filter being set, because that is the only condition under which the first row
	// names the filtered person — unfiltered, row zero is whoever happens to sort first, and the facet
	// would put a stranger's name on somebody else's id.
	const filteredSubject = search.subjectUserId == null ? undefined : rows[0]?.subject;
	const hasFilter = hasObservationFilter(search);
	const reset = () => onSearchChange(clearedObservationFilters());
	const patchFilter = (patch: Partial<ObservationsSearch>) => onSearchChange({ ...patch, page: 0 });

	return (
		<section aria-label="Practice review observations" className="space-y-4">
			<ObservationFilters
				search={search}
				onPatch={patchFilter}
				onReset={reset}
				groups={groups}
				practices={practices}
				people={people}
				total={observations?.page?.totalElements}
				scopedArtifact={rows[0]?.artifact}
				subjectName={filteredSubject?.name ?? filteredSubject?.login}
			/>
			{error == null ? (
				<ObservationResults
					workspaceSlug={workspaceSlug}
					practices={practiceRecords}
					state={resultsState(isLoading, rows, hasFilter ? reset : undefined)}
				/>
			) : (
				<QueryErrorAlert error={error} title="Couldn't load observations" onRetry={onRetry} />
			)}
			<TablePagination
				page={observations?.page?.number ?? search.page ?? 0}
				totalPages={observations?.page?.totalPages ?? 0}
				renderPageLink={(page, props) => (
					<Link
						{...props}
						to="/w/$workspaceSlug/admin/practices/reviews/observations"
						params={{ workspaceSlug }}
						search={(previous) => ({ ...previous, page: page === 0 ? undefined : page })}
					/>
				)}
			/>
		</section>
	);
}
