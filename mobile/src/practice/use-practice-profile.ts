import { useQuery } from "@tanstack/react-query";

import {
	listGroupsOptions,
	listPracticeGroupStandingsOptions,
	listPracticeStandingsOptions,
	listReviewedPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import { useWorkspace } from "@/workspace/workspace-context";

import { buildProfile, type GroupEntry } from "./profile";

/**
 * The developer's practice profile: the workspace's catalog and their standings in it, loaded together
 * and read as one answer. None of these reads delivers feedback, so the profile may load wherever it
 * is shown. Nothing is asked where practice reviews are off.
 */
export interface PracticeProfileQuery {
	data: GroupEntry[] | undefined;
	isPending: boolean;
	isError: boolean;
	isRefetching: boolean;
	refetch: () => void;
}

export function usePracticeProfile(): PracticeProfileQuery {
	const { workspaceSlug, practicesEnabled } = useWorkspace();
	const path = { workspaceSlug };
	const enabled = practicesEnabled;
	const groups = useQuery({
		...listGroupsOptions({ path, query: { visibleInPracticeDashboardsOnly: true } }),
		enabled,
	});
	const groupStandings = useQuery({ ...listPracticeGroupStandingsOptions({ path }), enabled });
	const reviewed = useQuery({ ...listReviewedPracticesOptions({ path }), enabled });
	const practiceStandings = useQuery({ ...listPracticeStandingsOptions({ path }), enabled });
	const all = [groups, groupStandings, reviewed, practiceStandings];
	const data =
		groups.data === undefined ||
		groupStandings.data === undefined ||
		reviewed.data === undefined ||
		practiceStandings.data === undefined
			? undefined
			: buildProfile({
					groups: groups.data,
					groupStandings: groupStandings.data,
					reviewed: reviewed.data,
					practiceStandings: practiceStandings.data,
				});
	return {
		data,
		isPending: all.some((query) => query.isPending),
		isError: all.some((query) => query.isError),
		isRefetching: all.some((query) => query.isRefetching),
		refetch: () => {
			for (const query of all) {
				void query.refetch();
			}
		},
	};
}
