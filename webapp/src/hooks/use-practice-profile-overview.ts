import { useQuery } from "@tanstack/react-query";

import { getPracticeProfileOverviewOptions } from "@/api/@tanstack/react-query.gen";
import { queryLoadState } from "@/components/common/panel-state";
import { EMPTY_OVERVIEW } from "@/components/practice-profile/compose-overview";

/**
 * How often the overview is re-asked while the page is open. The chip beside the title names the
 * latest run, which is a claim about now: a review can finish at any moment, and the reader sitting
 * on this page gives the cache no mount and no refocus to refresh it, so the chip would keep naming
 * yesterday's run while the list under it already showed today's. Slower than a watched run's own
 * poll, because nothing here is being waited on: the page is being read.
 */
const OVERVIEW_POLL_MS = 60_000;

/**
 * The practice profile's overview — what held, what changed and what the latest run looked at —
 * empty until it loads. The overview is read with no window, so the server compares the latest run
 * with the one before it.
 */
export function usePracticeProfileOverview(workspaceSlug: string) {
	const query = useQuery({
		...getPracticeProfileOverviewOptions({ path: { workspaceSlug } }),
		refetchInterval: OVERVIEW_POLL_MS,
	});
	return { overview: query.data ?? EMPTY_OVERVIEW, state: queryLoadState(query) };
}
