import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { statusFacetOptions } from "@/components/common/status-def";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import { WITHHOLDING_FAMILY_DEFS } from "@/components/practice-vocabulary/withholding-defs";
import { nonEmpty } from "@/lib/search-params";

import { listFacet } from "./AppliedFacetPills";
import type { PracticeFacetOption } from "./ResultFilters";
import { clearedScopeFilters, hasAppliedScope, readScope } from "./review-scope";
import type { FeedbackSearch } from "./review-search";
import { ReviewFilterBar } from "./ReviewFilterBar";
import type { ReviewPeople } from "./ReviewPersonFacet";
import type { ScopeRecordFilter } from "./ReviewScopeFilters";

const STATE_OPTIONS = statusFacetOptions(DELIVERY_STATE_DEFS);
const DESTINATION_OPTIONS = statusFacetOptions(FEEDBACK_DESTINATION_DEFS);
const WITHHELD_FAMILY_OPTIONS = statusFacetOptions(WITHHOLDING_FAMILY_DEFS);

/**
 * The facets this list owns, cleared: what the toolbar's Reset clears. The shared scope, the time
 * order included, is excluded, because "Clear scope" owns it.
 */
export function clearedFeedbackFacets(): Partial<FeedbackSearch> {
	return {
		state: undefined,
		destination: undefined,
		withheldFamily: undefined,
		location: undefined,
		withdrawn: undefined,
		disagreed: undefined,
		partlyDelivered: undefined,
	};
}

/** Every filter on this list, the shared scope included: what the empty list's button clears. */
export function clearedFeedbackFilters(): Partial<FeedbackSearch> {
	return { ...clearedFeedbackFacets(), ...clearedScopeFilters() };
}

function hasFeedbackFacet(search: FeedbackSearch): boolean {
	return (
		(search.state?.length ?? 0) > 0 ||
		(search.destination?.length ?? 0) > 0 ||
		(search.withheldFamily?.length ?? 0) > 0 ||
		search.location !== undefined ||
		search.withdrawn !== undefined ||
		search.disagreed !== undefined ||
		search.partlyDelivered !== undefined
	);
}

export function hasFeedbackFilter(search: FeedbackSearch): boolean {
	return hasFeedbackFacet(search) || hasAppliedScope("feedback", readScope(search));
}

export interface FeedbackFiltersProps {
	search: FeedbackSearch;
	/** Reports one changed facet. The caller sends the reader back to page one. */
	onPatch: (patch: Partial<FeedbackSearch>) => void;
	/** Clears this list's own facets. The shared scope stays. */
	onReset: () => void;
	/** Clears the shared scope, on this tab and every tab after it. */
	onClearScope: () => void;
	groups: FacetSource;
	practices: FacetSource<PracticeFacetOption>;
	people: ReviewPeople;
	/** How many rows the filters currently select, or `undefined` while that is unknown. */
	total: number | undefined;
	/** The one record the scope is filtered to, by name; absent for none, or for a selection. */
	record?: ScopeRecordFilter;
	/**
	 * The name of the developer `developerId` identifies. It must name *that* developer: reading it
	 * off the first row is right only while the filter is on.
	 */
	developerName?: string;
}

export function FeedbackFilters({
	search,
	onPatch,
	onReset,
	onClearScope,
	groups,
	practices,
	people,
	total,
	record,
	developerName,
}: FeedbackFiltersProps) {
	const state = listFacet("Delivery", STATE_OPTIONS, search.state, (values) =>
		onPatch({ state: nonEmpty(values) }),
	);
	const destination = listFacet("Destination", DESTINATION_OPTIONS, search.destination, (values) =>
		onPatch({ destination: nonEmpty(values) }),
	);
	const withheld = listFacet(
		"Why withheld",
		WITHHELD_FAMILY_OPTIONS,
		search.withheldFamily,
		(values) => onPatch({ withheldFamily: nonEmpty(values) }),
	);

	return (
		<ReviewFilterBar
			tab="feedback"
			scope={readScope(search)}
			onChange={onPatch}
			onClearScope={onClearScope}
			dateTitle="Composed"
			groups={groups}
			practices={practices}
			people={people}
			personName={developerName}
			record={record}
			facets={
				<>
					{state.control}
					{destination.control}
					{withheld.control}
				</>
			}
			pills={[...state.pills, ...destination.pills, ...withheld.pills]}
			referencePills={
				<>
					{search.location !== undefined && (
						<ReferenceFilterPill
							label="Where"
							value={FEEDBACK_LOCATION_DEFS[search.location].label}
							onClear={() => onPatch({ location: undefined })}
						/>
					)}
					{search.withdrawn !== undefined && (
						<ReferenceFilterPill
							label="Withdrawn"
							value={search.withdrawn ? "Only" : "Excluded"}
							onClear={() => onPatch({ withdrawn: undefined })}
						/>
					)}
					{search.disagreed !== undefined && (
						<ReferenceFilterPill
							label="Developer disagreed"
							value={search.disagreed ? "Only" : "Excluded"}
							onClear={() => onPatch({ disagreed: undefined })}
						/>
					)}
					{search.partlyDelivered !== undefined && (
						<ReferenceFilterPill
							label="Partly delivered"
							value={search.partlyDelivered ? "Only" : "Excluded"}
							onClear={() => onPatch({ partlyDelivered: undefined })}
						/>
					)}
				</>
			}
			hasFacet={hasFeedbackFacet(search)}
			onReset={onReset}
			total={total}
			noun={["piece of feedback", "pieces of feedback"]}
		/>
	);
}
