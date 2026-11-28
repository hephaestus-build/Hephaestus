import { useQuery } from "@tanstack/react-query";

import { listPracticeGroupsOptions, listPracticesOptions } from "@/api/@tanstack/react-query.gen";
import {
	groupFacetOptions,
	type PracticeFacetOption,
	practiceFacetOptions,
} from "@/components/admin/practice-reviews/ResultFilters";
import type { FacetSource } from "@/components/common/FacetMultiSelect";

export interface ReviewScopeSources {
	groups: FacetSource;
	practices: FacetSource<PracticeFacetOption>;
}

/**
 * The options of the shared scope's facets, for every tab under Practice reviews. TanStack Query
 * deduplicates by key, so a switch between tabs asks for none of them again.
 */
export function useReviewScopeSources(workspaceSlug: string): ReviewScopeSources {
	const groupsQuery = useQuery({ ...listPracticeGroupsOptions({ path: { workspaceSlug } }) });
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });
	return {
		groups: {
			options: groupFacetOptions(groupsQuery.data),
			isLoading: groupsQuery.isLoading,
			isError: groupsQuery.isError,
		},
		practices: {
			options: practiceFacetOptions(practicesQuery.data, groupsQuery.data),
			isLoading: practicesQuery.isLoading,
			isError: practicesQuery.isError,
		},
	};
}
