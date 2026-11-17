import {
	getPracticeWorkspaceOverviewOptions,
	getPracticeWorkspaceTilesOptions,
} from "@/api/@tanstack/react-query.gen";
import type { AcrossWorkspaceWindow } from "@/components/practices-across-the-workspace/across-workspace-copy";

// The route's loader and component share these reads, so they live outside the route file (`prefetchPageRead`).

/** Keep completed queries fresh across window changes and brief focus changes. */
const ACROSS_WORKSPACE_STALE_MS = 5 * 60 * 1000;

/** The page's two reads, shared by its loader and its component so both hit one cache entry each. */
export const overviewRead = (workspaceSlug: string) => ({
	...getPracticeWorkspaceOverviewOptions({ path: { workspaceSlug } }),
	staleTime: ACROSS_WORKSPACE_STALE_MS,
});
export const tilesRead = (workspaceSlug: string, window: AcrossWorkspaceWindow) => ({
	...getPracticeWorkspaceTilesOptions({ path: { workspaceSlug }, query: { window } }),
	staleTime: ACROSS_WORKSPACE_STALE_MS,
});
