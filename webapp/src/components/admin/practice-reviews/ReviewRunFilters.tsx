import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ResultCount } from "@/components/common/ResultCount";
import { statusFacetOptions } from "@/components/common/status-def";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { fromDateRange, toDateRange } from "@/lib/date-range-search";
import { nonEmpty } from "@/lib/search-params";

import { AppliedFacetPills, facetPills } from "./AppliedFacetPills";
import type { RunsSearch } from "./review-search";

const STATUS_OPTIONS = statusFacetOptions(REVIEW_STATUS_DEFS);

/** True when the reader has narrowed the list, which decides both the count's wording and whether
 * the empty state offers to clear anything. Derived here so the toolbar and the page it sits above
 * cannot disagree about what "filtered" means. */
export function hasRunFilter(search: RunsSearch): boolean {
	return (search.status?.length ?? 0) > 0 || search.from !== undefined || search.to !== undefined;
}

/**
 * Every field this toolbar can set, cleared. Exported so the list's empty state can offer the same
 * "clear all" the toolbar's Reset does without the two drifting into clearing different things —
 * and so a field added above cannot be forgotten in one of them.
 */
export function clearedRunFilters(): Partial<RunsSearch> {
	return { status: undefined, from: undefined, to: undefined };
}

export interface ReviewRunFiltersProps {
	search: RunsSearch;
	/** Reports one changed facet. The caller sends the reader back to page one. */
	onPatch: (patch: Partial<RunsSearch>) => void;
	onReset: () => void;
	/** How many reviews the current filters match. Absent while the answer is still on its way. */
	total: number | undefined;
}

export function ReviewRunFilters({ search, onPatch, onReset, total }: ReviewRunFiltersProps) {
	// Derived, not taken as a prop: the caller has `search` and nothing else, so a `hasFilter` it
	// computed could only ever be this same call — or a wrong one.
	const hasFilter = hasRunFilter(search);
	return (
		<FilterToolbar
			hasFilter={hasFilter}
			onReset={onReset}
			actions={<ResultCount total={total} noun={["review", "reviews"]} hasFilter={hasFilter} />}
		>
			<FacetMultiSelect
				title="Status"
				options={STATUS_OPTIONS}
				selected={search.status ?? []}
				onChange={(values) => onPatch({ status: nonEmpty(values) })}
			/>
			{/* "Requested", not "Started": the timestamp the rows show and this filters is the review's
			    `createdAt`, which is when it was enqueued, and a review can sit queued before a worker
			    claims it. */}
			<DateRangeFacet
				title="Requested"
				value={toDateRange(search)}
				onChange={(range) => onPatch(fromDateRange(range))}
			/>
			<AppliedFacetPills
				pills={facetPills("Status", STATUS_OPTIONS, search.status, (values) =>
					onPatch({ status: nonEmpty(values) }),
				)}
			/>
		</FilterToolbar>
	);
}
