import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { statusFacetOptions } from "@/components/common/status-def";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { nonEmpty } from "@/lib/search-params";

import { listFacet } from "./AppliedFacetPills";
import {
	disagreementFacet,
	hasObservationFilter,
	markFilterPills,
	type PracticeFacetOption,
} from "./ResultFilters";
import { clearedScopeFilters, hasAppliedScope, readScope } from "./review-scope";
import type { ProblemsSearch } from "./review-search";
import { ReviewFilterBar } from "./ReviewFilterBar";
import type { ReviewPeople } from "./ReviewPersonFacet";
import type { ScopeRecordFilter } from "./ReviewScopeFilters";

const SEVERITY_OPTIONS = statusFacetOptions(SEVERITY_DEFS);

export function clearedProblemFacets(): Partial<ProblemsSearch> {
	return {
		severity: undefined,
		currentness: undefined,
		markedIncorrect: undefined,
		accepted: undefined,
		disagreed: undefined,
	};
}

export function clearedProblemFilters(): Partial<ProblemsSearch> {
	return { ...clearedProblemFacets(), ...clearedScopeFilters() };
}

function hasProblemFacet(search: ProblemsSearch): boolean {
	return (
		(search.severity?.length ?? 0) > 0 ||
		search.currentness !== undefined ||
		hasObservationFilter(search)
	);
}

export function hasProblemFilter(search: ProblemsSearch): boolean {
	return hasProblemFacet(search) || hasAppliedScope("problems", readScope(search));
}

export interface ProblemFiltersProps {
	search: ProblemsSearch;
	onPatch: (patch: Partial<ProblemsSearch>) => void;
	onReset: () => void;
	onClearScope: () => void;
	groups: FacetSource;
	practices: FacetSource<PracticeFacetOption>;
	people: ReviewPeople;
	total: number | undefined;
	record?: ScopeRecordFilter;
	subjectName?: string;
}

export function ProblemFilters({
	search,
	onPatch,
	onReset,
	onClearScope,
	groups,
	practices,
	people,
	total,
	record,
	subjectName,
}: ProblemFiltersProps) {
	const severity = listFacet("Severity", SEVERITY_OPTIONS, search.severity, (values) =>
		onPatch({ severity: nonEmpty(values) }),
	);
	const disagreement = disagreementFacet(search, onPatch);
	return (
		<ReviewFilterBar
			tab="problems"
			scope={readScope(search)}
			onChange={onPatch}
			onClearScope={onClearScope}
			dateTitle="Recorded"
			groups={groups}
			practices={practices}
			people={people}
			personName={subjectName}
			record={record}
			facets={
				<>
					{severity.control}
					{disagreement.control}
				</>
			}
			pills={[...severity.pills, ...disagreement.pills]}
			referencePills={markFilterPills(search, onPatch)}
			hasFacet={hasProblemFacet(search)}
			onReset={onReset}
			total={total}
			noun={["problem", "problems"]}
		/>
	);
}
