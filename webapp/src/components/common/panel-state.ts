import type { UseQueryResult } from "@tanstack/react-query";

/**
 * What a region shows while its data is in flight, failed, or in.
 *
 * Not TanStack Query's own `pending | error | success`: a panel's ready state carries data the route
 * composed from several queries, and it treats `data === undefined` as still loading.
 */
export type PanelState<TReady> =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| ({ status: "ready" } & TReady);

/**
 * One query folded into a panel state. `settled` names the state a response becomes, so a panel
 * whose data can arrive in a shape it cannot show returns that state from here too.
 */
export function panelState<TData, TSettled>(
	query: UseQueryResult<TData>,
	settled: (data: TData) => TSettled,
): PanelState<never> | TSettled {
	if (query.isError) {
		return { status: "error", error: query.error, onRetry: () => void query.refetch() };
	}
	if (query.isPending) return { status: "loading" };
	return settled(query.data);
}
