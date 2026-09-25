import { useQuery } from "@tanstack/react-query";

import { getPracticeProfileOverviewOptions } from "@/api/@tanstack/react-query.gen";
import { queryLoadState } from "@/components/common/panel-state";
import { EMPTY_OVERVIEW } from "@/components/practice-profile/compose-overview";

/**
 * The practice profile's overview — what held, what changed and what the latest run looked at —
 * empty until it loads. The overview is read with no window, so the server compares the latest run
 * with the one before it.
 */
export function usePracticeProfileOverview(workspaceSlug: string) {
	const query = useQuery(getPracticeProfileOverviewOptions({ path: { workspaceSlug } }));
	return { overview: query.data ?? EMPTY_OVERVIEW, state: queryLoadState(query) };
}
