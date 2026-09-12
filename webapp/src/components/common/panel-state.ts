import type { UseQueryResult } from "@tanstack/react-query";

export type PanelState<TReady> =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| ({ status: "ready" } & TReady);

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
