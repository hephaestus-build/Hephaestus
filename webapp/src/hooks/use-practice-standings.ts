import { useQuery } from "@tanstack/react-query";

import {
	listGroupsOptions,
	listPracticeGroupStandingsOptions,
	listPracticeStandingsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeGroup, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";
import {
	combinePanelStates,
	type LoadState,
	queryLoadState,
} from "@/components/common/panel-state";

export interface PracticeStandings {
	/** The groups the practice profile shows, in catalog order. */
	groups: PracticeGroup[];
	/** The developer's standing in each group, keyed by group slug. */
	groupStandings: Record<string, PracticeGroupStanding>;
	practiceStandings: PracticeStanding[];
	/** `groupPracticeStandings(practiceStandings)`. */
	practicesByGroup: Record<string, PracticeStanding[] | undefined>;
	/** The three queries as one: the page shows them together, so they load and fail as one. */
	state: LoadState;
}

/** The practice standings under their group's slug; a practice in no group is in none of them. */
export function groupPracticeStandings(
	practiceStandings: PracticeStanding[],
): Record<string, PracticeStanding[] | undefined> {
	const byGroup: Record<string, PracticeStanding[] | undefined> = {};
	for (const practice of practiceStandings) {
		if (practice.groupSlug) (byGroup[practice.groupSlug] ??= []).push(practice);
	}
	return byGroup;
}

/**
 * The developer's practice standings in one workspace — the groups, the standing in each and the
 * standing on every practice — as the practice profile and the drawer over it read them. Only
 * that route reads this; it is a file of its own because three queries folded into one state is
 * what keeps the route readable.
 */
export function usePracticeStandings(workspaceSlug: string): PracticeStandings {
	const groupsQuery = useQuery(
		listGroupsOptions({
			path: { workspaceSlug },
			query: { visibleInPracticeDashboardsOnly: true },
		}),
	);
	const groupStandingsQuery = useQuery(
		listPracticeGroupStandingsOptions({ path: { workspaceSlug } }),
	);
	const standingsQuery = useQuery(listPracticeStandingsOptions({ path: { workspaceSlug } }));
	const practiceStandings = standingsQuery.data ?? [];
	return {
		groups: groupsQuery.data ?? [],
		groupStandings: Object.fromEntries(
			(groupStandingsQuery.data ?? []).map((standing) => [standing.groupSlug, standing]),
		),
		practiceStandings,
		practicesByGroup: groupPracticeStandings(practiceStandings),
		state: combinePanelStates(
			[groupsQuery, groupStandingsQuery, standingsQuery].map((query) => queryLoadState(query)),
		),
	};
}
