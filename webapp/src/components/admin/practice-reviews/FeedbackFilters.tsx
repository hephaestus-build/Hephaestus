import type { ReviewedWorkRef } from "@/api/types.gen";
import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect } from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { ResultCount } from "@/components/common/ResultCount";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-outcome-defs";
import {
	DELIVERY_PLACE_DEFS,
	FILTERABLE_PLACES,
} from "@/components/practice-vocabulary/delivery-place-defs";
import { statusFacetOptions } from "@/components/practice-vocabulary/status-def";
import { WITHHOLDING_FAMILY_DEFS } from "@/components/practice-vocabulary/withholding-defs";
import { fromDateRange, toDateRange } from "@/lib/date-range-search";
import { nonEmpty } from "@/lib/search-params";

import { AppliedFacetPills, facetPills } from "./AppliedFacetPills";
import type { FeedbackSearch } from "./review-search";
import { reviewArtifactScopeLabel } from "./ReviewArtifact";
import { type ReviewPeople, ReviewPersonFacet } from "./ReviewPersonFacet";

const OUTCOME_OPTIONS = statusFacetOptions(DELIVERY_STATE_DEFS);
// The one facet that offers a subset: a place nothing is ever written to would be a filter with no
// rows behind it. Narrowing here rather than in `statusFacetOptions` keeps the registry total, so a
// value the server adds still renders on a row.
const PLACE_OPTIONS = statusFacetOptions(DELIVERY_PLACE_DEFS).filter((option) =>
	FILTERABLE_PLACES.includes(option.value),
);
const WITHHELD_FAMILY_OPTIONS = statusFacetOptions(WITHHOLDING_FAMILY_DEFS);

/**
 * Every field this toolbar can set, cleared. Exported so the list's empty state can offer the same
 * "clear all" the toolbar's Reset does without the two drifting into clearing different things.
 */
export function clearedFeedbackFilters(): Partial<FeedbackSearch> {
	return {
		page: 0,
		deliveryState: undefined,
		withheldFamily: undefined,
		channel: undefined,
		agentJobId: undefined,
		artifactKind: undefined,
		artifactId: undefined,
		recipientUserId: undefined,
		from: undefined,
		to: undefined,
	};
}

export function hasFeedbackFilter(search: FeedbackSearch): boolean {
	return (
		(search.deliveryState?.length ?? 0) > 0 ||
		(search.withheldFamily?.length ?? 0) > 0 ||
		(search.channel?.length ?? 0) > 0 ||
		search.agentJobId !== undefined ||
		search.artifactKind !== undefined ||
		search.recipientUserId !== undefined ||
		search.from !== undefined ||
		search.to !== undefined
	);
}

export interface FeedbackFiltersProps {
	search: FeedbackSearch;
	/** Reports one changed facet. The caller sends the reader back to page one. */
	onPatch: (patch: Partial<FeedbackSearch>) => void;
	onReset: () => void;
	people: ReviewPeople;
	/** How many rows the filters currently select, or `undefined` while that is unknown. */
	total: number | undefined;
	/**
	 * The work `artifactKind`/`artifactId` points at, so the pill can name it rather than print an
	 * id. Absent until a row carrying that artifact has arrived.
	 */
	scopedArtifact?: ReviewedWorkRef;
	/**
	 * The name of the person `recipientUserId` identifies. It must name *that* person: reading it off
	 * the first row is right only while the filter is on.
	 */
	recipientName?: string;
}

export function FeedbackFilters({
	search,
	onPatch,
	onReset,
	people,
	total,
	scopedArtifact,
	recipientName,
}: FeedbackFiltersProps) {
	const hasFilter = hasFeedbackFilter(search);

	return (
		<FilterToolbar
			hasFilter={hasFilter}
			onReset={onReset}
			actions={
				<ResultCount
					total={total}
					noun={["piece of feedback", "pieces of feedback"]}
					hasFilter={hasFilter}
				/>
			}
		>
			<div className="flex flex-wrap gap-2">
				<FacetMultiSelect
					title="Outcome"
					options={OUTCOME_OPTIONS}
					selected={search.deliveryState ?? []}
					onChange={(values) => onPatch({ deliveryState: nonEmpty(values) })}
				/>
				<FacetMultiSelect
					title="Place"
					options={PLACE_OPTIONS}
					selected={search.channel ?? []}
					onChange={(values) => onPatch({ channel: nonEmpty(values) })}
				/>
				<FacetMultiSelect
					title="Why withheld"
					options={WITHHELD_FAMILY_OPTIONS}
					selected={search.withheldFamily ?? []}
					onChange={(values) => onPatch({ withheldFamily: nonEmpty(values) })}
				/>
				<ReviewPersonFacet
					title="Recipient"
					people={people}
					selected={search.recipientUserId}
					onChange={(recipientUserId) => onPatch({ recipientUserId })}
					fallbackName={recipientName}
				/>
				{/* "Composed" rather than "Date": this range filters when the feedback was written, which
				    is not when it was delivered and not when the observation behind it was made. */}
				<DateRangeFacet
					title="Composed"
					value={toDateRange(search)}
					onChange={(range) => onPatch(fromDateRange(range))}
				/>
			</div>
			<AppliedFacetPills
				pills={[
					...facetPills("Outcome", OUTCOME_OPTIONS, search.deliveryState, (values) =>
						onPatch({ deliveryState: nonEmpty(values) }),
					),
					...facetPills("Place", PLACE_OPTIONS, search.channel, (values) =>
						onPatch({ channel: nonEmpty(values) }),
					),
					...facetPills("Why withheld", WITHHELD_FAMILY_OPTIONS, search.withheldFamily, (values) =>
						onPatch({ withheldFamily: nonEmpty(values) }),
					),
				]}
			/>
			{search.agentJobId && (
				<ReferenceFilterPill
					label="Review"
					value={search.agentJobId}
					onClear={() => onPatch({ agentJobId: undefined })}
				/>
			)}
			{search.artifactKind && (
				<ReferenceFilterPill
					label="Reviewed work"
					value={reviewArtifactScopeLabel(search.artifactKind, search.artifactId, scopedArtifact)}
					onClear={() => onPatch({ artifactKind: undefined, artifactId: undefined })}
				/>
			)}
		</FilterToolbar>
	);
}
