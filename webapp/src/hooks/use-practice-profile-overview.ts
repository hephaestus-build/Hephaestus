import { useQuery } from "@tanstack/react-query";

import { getPracticeProfileOverviewOptions } from "@/api/@tanstack/react-query.gen";
import { queryLoadState } from "@/components/common/panel-state";
import { EMPTY_OVERVIEW } from "@/components/practice-profile/compose-overview";

/**
 * How often the page's review-backed reads refresh while it is open: a review can finish at any
 * moment, and a reader sitting on the page gives the cache no mount or refocus to notice it.
 */
export const PRACTICE_PROFILE_POLL_MS = 60_000;

/**
 * The practice profile's overview — what held, what changed and what the latest run looked at —
 * empty until it loads. The overview is read with no window, so the server compares the latest run
 * with the one before it.
 */
export function usePracticeProfileOverview(workspaceSlug: string, enabled = true) {
	const query = useQuery({
		...getPracticeProfileOverviewOptions({ path: { workspaceSlug } }),
		refetchInterval: PRACTICE_PROFILE_POLL_MS,
		enabled,
	});
	return { overview: query.data ?? EMPTY_OVERVIEW, state: queryLoadState(query) };
}
