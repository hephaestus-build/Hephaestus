import { createContext, useContext } from "react";

/**
 * Whether the workspace SSE stream is down and the surface must fall back to polling.
 *
 * The integrations layout owns the one `useSyncEvents` stream for the whole section and publishes
 * its health here, so every page below can feed {@link syncPollInterval} without prop-drilling
 * through the generated query-options spreads.
 *
 * The default is `false` (healthy): a component rendered outside the layout — a story, a test
 * harness — has no stream to be told about, and assuming the degraded fast-poll there would invent
 * traffic nobody asked for.
 */
const SyncLivenessContext = createContext(false);

export function SyncLivenessProvider({
	livePushUnavailable,
	children,
}: {
	livePushUnavailable: boolean;
	children: React.ReactNode;
}) {
	return <SyncLivenessContext value={livePushUnavailable}>{children}</SyncLivenessContext>;
}

export function useLivePushUnavailable(): boolean {
	return useContext(SyncLivenessContext);
}
