import type { FacetSource } from "@/components/common/FacetMultiSelect";

import { practiceGroups, workspacePractices } from "./fixtures";
import { groupFacetOptions, type PracticeFacetOption, practiceFacetOptions } from "./ResultFilters";

/** The options of the shared scope's facets, loaded, for the stories of every review list. */
export const STORY_GROUPS: FacetSource = {
	options: groupFacetOptions(practiceGroups),
	isLoading: false,
	isError: false,
};

export const STORY_PRACTICES: FacetSource<PracticeFacetOption> = {
	options: practiceFacetOptions(workspacePractices, practiceGroups),
	isLoading: false,
	isError: false,
};
