import type { FacetSource } from "@/components/common/FacetMultiSelect";

import { practiceGroups, workspaceMembers, workspacePractices } from "./fixtures";
import { groupFacetOptions, type PracticeFacetOption, practiceFacetOptions } from "./ResultFilters";
import type { ReviewPeople } from "./ReviewPersonFacet";

/** The options of the shared scope's facets, loaded, for the stories of every review list. */
export const STORY_PEOPLE: ReviewPeople = {
	options: workspaceMembers.map((member) => ({
		accountId: member.account.id,
		label: member.account.displayName ?? `#${member.account.id}`,
		secondary: member.account.identities[0]?.login ?? undefined,
	})),
	capped: false,
	named: true,
	isLoading: false,
	isError: false,
};

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
