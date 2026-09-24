import type { UseQueryResult } from "@tanstack/react-query";

/**
 * What a region shows while its data is in flight, failed, or in.
 *
 * Not TanStack Query's own `pending | error | success`: a panel's ready state carries data the
 * route composed from several queries, and it treats `data === undefined` as still loading.
 */
export type PanelState<TReady> =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| ({ status: "ready" } & TReady);

/** One query's state, with `settled` shaping its data into the ready branch. */
export function panelState<TData, TSettled>(
	query: UseQueryResult<TData>,
	settled: (data: TData) => TSettled,
): PanelState<never> | TSettled {
	if (query.isError) {
		return {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	}
	if (query.isPending) {
		return { status: "loading" };
	}
	return settled(query.data);
}

/**
 * A panel's state with nothing in its ready branch: what a hook reports beside data it hands back
 * in every state — empty lists while loading, so a page can draw its skeletons in their shape.
 */
export type LoadState = PanelState<object>;

/** The slice of a TanStack query result the state reads, so a hook hands the result in as it is. */
interface QueryLike {
	isPending: boolean;
	isError: boolean;
	error: unknown;
	refetch: () => unknown;
}

/** One query's state; a failed query retries itself. */
export function queryLoadState(query: QueryLike): LoadState {
	if (query.isError) {
		return {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	}
	return query.isPending ? { status: "loading" } : { status: "ready" };
}

/**
 * Several panels' states as one, for a region that shows them together: failed as soon as one has
 * failed, with one retry for every failure; otherwise loading until every one is in.
 */
export function combinePanelStates(states: readonly LoadState[]): LoadState {
	const failed = states.flatMap((state) => (state.status === "error" ? [state] : []));
	const [first] = failed;
	if (first) {
		return {
			status: "error",
			error: first.error,
			onRetry: () => {
				for (const state of failed) {
					state.onRetry();
				}
			},
		};
	}
	return states.some((state) => state.status === "loading")
		? { status: "loading" }
		: { status: "ready" };
}

/**
 * The state as the three props a region that renders through every branch takes — a page whose
 * header and tabs stand while the body loads — so the route derives them once.
 */
export function loadProps(state: LoadState): {
	isLoading: boolean;
	error?: unknown;
	onRetry?: () => void;
} {
	return state.status === "error"
		? { isLoading: false, error: state.error, onRetry: state.onRetry }
		: { isLoading: state.status === "loading" };
}
