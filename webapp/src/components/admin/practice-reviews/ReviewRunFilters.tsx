import { useId } from "react";

import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ResultCount } from "@/components/common/ResultCount";
import { statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	REVIEW_STATUS_DEFS,
	type ReviewStatus,
} from "@/components/practice-vocabulary/review-status-defs";
import { Field, FieldLabel } from "@/components/ui/field";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { fromDateRange, toDateRange } from "@/lib/date-range-search";

import type { RunsSearch } from "./review-search";

const STATUSES = statusValues(REVIEW_STATUS_DEFS);
/** The "no filter" sentinel. A `Select` has to hold some value, and `undefined` is not one. */
const ALL_STATUSES = "ALL";
const STATUS_ITEMS: { value: string; label: string }[] = [
	{ value: ALL_STATUSES, label: "All statuses" },
	...STATUSES.map((status) => ({ value: status, label: REVIEW_STATUS_DEFS[status].label })),
];

function isReviewStatus(value: string | null): value is ReviewStatus {
	return STATUSES.some((status) => status === value);
}

/** True when the reader has narrowed the list, which decides both the count's wording and whether
 * the empty state offers to clear anything. Derived here so the toolbar and the page it sits above
 * cannot disagree about what "filtered" means. */
export function hasRunFilter(search: RunsSearch): boolean {
	return search.status !== undefined || search.from !== undefined || search.to !== undefined;
}

/**
 * Every field this toolbar can set, cleared. Exported so the list's empty state can offer the same
 * "clear all" the toolbar's Reset does without the two drifting into clearing different things —
 * and so a field added above cannot be forgotten in one of them.
 */
export function clearedRunFilters(): Partial<RunsSearch> {
	return { page: 0, status: undefined, from: undefined, to: undefined };
}

/**
 * Renders the row's own `StatusBadge` rather than a lookalike, so choosing a filter never means
 * matching a word to a tag from memory.
 */
function StatusItemLabel({ value }: { value: string }) {
	if (!isReviewStatus(value)) {
		return <span className="text-muted-foreground">All statuses</span>;
	}
	return <StatusBadge def={REVIEW_STATUS_DEFS[value]} />;
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
	const statusId = useId();
	const statusLabelId = useId();
	// Derived, not taken as a prop: the caller has `search` and nothing else, so a `hasFilter` it
	// computed could only ever be this same call — or a wrong one.
	const hasFilter = hasRunFilter(search);
	return (
		<FilterToolbar
			hasFilter={hasFilter}
			onReset={onReset}
			actions={<ResultCount total={total} noun={["review", "reviews"]} hasFilter={hasFilter} />}
		>
			<Field orientation="horizontal" className="w-auto max-w-full flex-wrap text-sm">
				<FieldLabel id={statusLabelId} htmlFor={statusId} className="text-muted-foreground">
					Status
				</FieldLabel>
				<Select
					items={STATUS_ITEMS}
					value={search.status ?? ALL_STATUSES}
					onValueChange={(value) => onPatch({ status: isReviewStatus(value) ? value : undefined })}
				>
					<SelectTrigger id={statusId} size="sm" className="w-48 max-w-full">
						<SelectValue>{(value: string) => <StatusItemLabel value={value} />}</SelectValue>
					</SelectTrigger>
					<SelectContent aria-labelledby={statusLabelId}>
						{STATUS_ITEMS.map((item) => (
							<SelectItem key={item.value} value={item.value}>
								<StatusItemLabel value={item.value} />
							</SelectItem>
						))}
					</SelectContent>
				</Select>
			</Field>
			{/* "Requested", not "Started": the timestamp the rows show and this filters is the review's
			    `createdAt`, which is when it was enqueued, and a review can sit queued before a worker
			    claims it. */}
			<DateRangeFacet
				title="Requested"
				value={toDateRange(search)}
				onChange={(range) => onPatch(fromDateRange(range))}
			/>
		</FilterToolbar>
	);
}
