import type { ReviewArtifact } from "@/api/types.gen";
import { DateRangeFacet } from "@/components/common/DateRangeFacet";
import {
	FacetMultiSelect,
	type FacetOption,
	type FacetSource,
} from "@/components/common/FacetMultiSelect";
import { FilterToolbar } from "@/components/common/FilterToolbar";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { ResultCount } from "@/components/common/ResultCount";
import { ASSESSMENT_DEFS } from "@/components/practice-vocabulary/assessment-defs";
import { ASSESSMENT_STATUS_DEFS } from "@/components/practice-vocabulary/assessment-status-defs";
import { PRESENCE_DEFS } from "@/components/practice-vocabulary/presence-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { statusFacetOptions } from "@/components/practice-vocabulary/status-def";
import { fromDateRange, toDateRange } from "@/lib/date-range-search";
import { nonEmpty } from "@/lib/search-params";

import { AppliedFacetPills, facetPills } from "./AppliedFacetPills";
import { ObservationSortSelect } from "./ObservationSortSelect";
import type { ObservationsSearch } from "./review-search";
import { reviewArtifactScopeLabel } from "./ReviewArtifact";
import { type ReviewPeople, ReviewPersonFacet } from "./ReviewPersonFacet";

/** Every option wears the badge its rows wear; see the note on `FeedbackFilters`' facets. */
const ASSESSMENT_OPTIONS = statusFacetOptions(ASSESSMENT_DEFS);
const ASSESSMENT_STATUS_OPTIONS = statusFacetOptions(ASSESSMENT_STATUS_DEFS);
const PRESENCE_OPTIONS = statusFacetOptions(PRESENCE_DEFS);
const SEVERITY_OPTIONS = statusFacetOptions(SEVERITY_DEFS);

/**
 * Every field this toolbar can set, cleared — `order` deliberately excluded, because sorting does not
 * narrow anything and Reset leaves it alone. Exported so the list's empty state clears exactly what
 * Reset clears.
 */
export function clearedObservationFilters(): Partial<ObservationsSearch> {
	return {
		page: 0,
		groupSlug: undefined,
		practiceSlug: undefined,
		assessmentStatus: undefined,
		presence: undefined,
		assessment: undefined,
		severity: undefined,
		agentJobId: undefined,
		artifactKind: undefined,
		artifactId: undefined,
		subjectUserId: undefined,
		from: undefined,
		to: undefined,
	};
}

export function hasObservationFilter(search: ObservationsSearch): boolean {
	return (
		(search.groupSlug?.length ?? 0) > 0 ||
		(search.practiceSlug?.length ?? 0) > 0 ||
		(search.assessmentStatus?.length ?? 0) > 0 ||
		(search.presence?.length ?? 0) > 0 ||
		(search.assessment?.length ?? 0) > 0 ||
		(search.severity?.length ?? 0) > 0 ||
		search.agentJobId !== undefined ||
		search.artifactKind !== undefined ||
		search.subjectUserId !== undefined ||
		search.from !== undefined ||
		search.to !== undefined
	);
}

type NamedGroup = { slug: string; name: string };

export function groupFacetOptions(groups: readonly NamedGroup[] | undefined): FacetOption[] {
	return (groups ?? []).map((group) => ({ value: group.slug, label: group.name }));
}

export function practiceFacetOptions(
	practices: readonly { slug: string; name: string; groupSlug?: string }[] | undefined,
	groups: readonly NamedGroup[] | undefined,
): FacetOption[] {
	return (practices ?? []).map((practice) => ({
		value: practice.slug,
		label: practice.name,
		description: (groups ?? []).find((group) => group.slug === practice.groupSlug)?.name,
	}));
}

export interface ObservationFiltersProps {
	search: ObservationsSearch;
	/** Reports one changed facet. The caller sends the reader back to page one. */
	onPatch: (patch: Partial<ObservationsSearch>) => void;
	onReset: () => void;
	groups: FacetSource;
	practices: FacetSource;
	people: ReviewPeople;
	total: number | undefined;
	/**
	 * The work `artifactKind`/`artifactId` points at, so the pill can name it rather than print an
	 * id. Absent until a row carrying that artifact has arrived.
	 */
	scopedArtifact?: ReviewArtifact;
	/**
	 * The name of the person `subjectUserId` identifies. It must name *that* person: reading it off
	 * the first row is right only while the filter is on.
	 */
	subjectName?: string;
}

export function ObservationFilters({
	search,
	onPatch,
	onReset,
	groups,
	practices,
	people,
	total,
	scopedArtifact,
	subjectName,
}: ObservationFiltersProps) {
	const hasFilter = hasObservationFilter(search);

	return (
		<FilterToolbar
			hasFilter={hasFilter}
			onReset={onReset}
			actions={
				<>
					{/* Sort sits with the count rather than among the facets: it does not narrow the set,
					    and `Reset` deliberately leaves it alone. */}
					<ObservationSortSelect value={search.order} onChange={(order) => onPatch({ order })} />
					<ResultCount total={total} noun={["observation", "observations"]} hasFilter={hasFilter} />
				</>
			}
		>
			<div className="flex flex-wrap gap-2">
				<FacetMultiSelect
					title="Group"
					options={groups.options}
					selected={search.groupSlug ?? []}
					onChange={(values) => onPatch({ groupSlug: nonEmpty(values) })}
					disabled={groups.isLoading}
					emptyLabel={groups.isError ? "Could not load groups" : "No groups available"}
				/>
				<FacetMultiSelect
					title="Practice"
					options={practices.options}
					selected={search.practiceSlug ?? []}
					onChange={(values) => onPatch({ practiceSlug: nonEmpty(values) })}
					disabled={practices.isLoading}
					emptyLabel={practices.isError ? "Could not load practices" : "No practices available"}
				/>
				<FacetMultiSelect
					title="Result"
					options={ASSESSMENT_OPTIONS}
					selected={search.assessment ?? []}
					onChange={(values) => onPatch({ assessment: nonEmpty(values) })}
				/>
				<FacetMultiSelect
					title="Severity"
					options={SEVERITY_OPTIONS}
					selected={search.severity ?? []}
					onChange={(values) => onPatch({ severity: nonEmpty(values) })}
				/>
				<FacetMultiSelect
					title="Assessment status"
					options={ASSESSMENT_STATUS_OPTIONS}
					selected={search.assessmentStatus ?? []}
					onChange={(values) => onPatch({ assessmentStatus: nonEmpty(values) })}
				/>
				<FacetMultiSelect
					title="Presence"
					options={PRESENCE_OPTIONS}
					selected={search.presence ?? []}
					onChange={(values) => onPatch({ presence: nonEmpty(values) })}
				/>
				<ReviewPersonFacet
					title="Developer"
					people={people}
					selected={search.subjectUserId}
					onChange={(subjectUserId) => onPatch({ subjectUserId })}
					fallbackName={subjectName}
				/>
				{/* "Observed", not "Date": this range filters `observedAt`, while the same control on
				    Delivery filters when the feedback was composed. */}
				<DateRangeFacet
					title="Observed"
					value={toDateRange(search)}
					onChange={(range) => onPatch(fromDateRange(range))}
				/>
			</div>
			<AppliedFacetPills
				pills={[
					...facetPills("Group", groups.options, search.groupSlug, (values) =>
						onPatch({ groupSlug: nonEmpty(values) }),
					),
					...facetPills("Practice", practices.options, search.practiceSlug, (values) =>
						onPatch({ practiceSlug: nonEmpty(values) }),
					),
					...facetPills("Result", ASSESSMENT_OPTIONS, search.assessment, (values) =>
						onPatch({ assessment: nonEmpty(values) }),
					),
					...facetPills("Severity", SEVERITY_OPTIONS, search.severity, (values) =>
						onPatch({ severity: nonEmpty(values) }),
					),
					...facetPills(
						"Assessment status",
						ASSESSMENT_STATUS_OPTIONS,
						search.assessmentStatus,
						(values) => onPatch({ assessmentStatus: nonEmpty(values) }),
					),
					...facetPills("Presence", PRESENCE_OPTIONS, search.presence, (values) =>
						onPatch({ presence: nonEmpty(values) }),
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
