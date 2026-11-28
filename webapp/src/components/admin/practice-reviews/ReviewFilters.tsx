import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { statusFacetOptions } from "@/components/common/status-def";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { nonEmpty } from "@/lib/search-params";

import { listFacet } from "./AppliedFacetPills";
import type { PracticeFacetOption } from "./ResultFilters";
import { QUEUE_STATE_LABELS } from "./review-queue";
import { clearedScopeFilters, hasAppliedScope, readScope } from "./review-scope";
import type { ReviewsSearch } from "./review-search";
import { ReviewFilterBar } from "./ReviewFilterBar";
import type { ScopeRecordFilter } from "./ReviewScopeFilters";

const STATUS_OPTIONS = statusFacetOptions(REVIEW_STATUS_DEFS);

function hasReviewFacet(search: ReviewsSearch): boolean {
	return (search.status?.length ?? 0) > 0 || search.queueState !== undefined;
}

export function hasReviewFilter(search: ReviewsSearch): boolean {
	return hasReviewFacet(search) || hasAppliedScope("reviews", readScope(search));
}

export function clearedReviewFacets(): Partial<ReviewsSearch> {
	return { status: undefined, queueState: undefined };
}

export function clearedReviewFilters(): Partial<ReviewsSearch> {
	return { ...clearedReviewFacets(), ...clearedScopeFilters() };
}

export interface ReviewFiltersProps {
	search: ReviewsSearch;
	onPatch: (patch: Partial<ReviewsSearch>) => void;
	onReset: () => void;
	onClearScope: () => void;
	groups: FacetSource;
	practices: FacetSource<PracticeFacetOption>;
	total: number | undefined;
	record?: ScopeRecordFilter;
}

export function ReviewFilters({
	search,
	onPatch,
	onReset,
	onClearScope,
	groups,
	practices,
	total,
	record,
}: ReviewFiltersProps) {
	const status = listFacet("Status", STATUS_OPTIONS, search.status, (values) =>
		onPatch({ status: nonEmpty(values) }),
	);
	return (
		<ReviewFilterBar
			tab="reviews"
			scope={readScope(search)}
			onChange={onPatch}
			onClearScope={onClearScope}
			dateTitle="Created"
			groups={groups}
			practices={practices}
			record={record}
			facets={status.control}
			pills={status.pills}
			referencePills={
				search.queueState !== undefined && (
					<ReferenceFilterPill
						label="Queue now"
						value={QUEUE_STATE_LABELS[search.queueState]}
						onClear={() => onPatch({ queueState: undefined })}
					/>
				)
			}
			hasFacet={hasReviewFacet(search)}
			onReset={onReset}
			total={total}
			noun={["review", "reviews"]}
		/>
	);
}
