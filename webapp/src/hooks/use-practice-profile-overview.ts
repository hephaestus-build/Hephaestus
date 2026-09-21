import { useQuery } from "@tanstack/react-query";

import { getPracticeProfileOverviewOptions } from "@/api/@tanstack/react-query.gen";
import { type LoadState, queryLoadState } from "@/components/common/panel-state";
import {
	type ComposedOverview,
	composeOverview,
	EMPTY_OVERVIEW,
} from "@/components/practice-profile/compose-overview";

export interface PracticeProfileOverview {
	/** The overview composed into the page's and each group level's sentences. */
	overview: ComposedOverview;
	state: LoadState;
}

/**
 * The practice profile's overview — what held, what changed and what the latest run looked at —
 * composed into the page's sentences. The overview is read with no window, so the server compares
 * the latest run with the one before it. Only the practice profile reads this; it is a file of its
 * own so the route stays a route and the composition has one home beside the query.
 */
export function usePracticeProfileOverview(workspaceSlug: string): PracticeProfileOverview {
	const query = useQuery(getPracticeProfileOverviewOptions({ path: { workspaceSlug } }));
	return {
		overview: composeOverview(query.data ?? EMPTY_OVERVIEW),
		state: queryLoadState(query),
	};
}
