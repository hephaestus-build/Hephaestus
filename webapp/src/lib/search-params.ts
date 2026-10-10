import { retainSearchParams, stripSearchParams, useNavigate } from "@tanstack/react-router";
import { z } from "zod";

export const multiValue = z
	.union([z.string().transform((value) => [value]), z.array(z.string())])
	.transform((values) => [...new Set(values)])
	.optional()
	.catch(undefined);

export function nonEmpty<T>(values: T[]): T[] | undefined {
	return values.length > 0 ? values : undefined;
}

/**
 * Search params that follow the reader to every link into the route — a range picked once — and
 * leave the URL while they hold their default. Each middleware works on what the ones after it
 * return, so the first has the last word: the strip goes first, because a link that names none of
 * `keys` gets the current value from the retain, and only a strip that runs after can drop a default.
 */
export function carriedSearchParams<TSearch extends object>(
	keys: readonly (keyof TSearch)[],
	defaults: Partial<TSearch>,
) {
	return [stripSearchParams<TSearch>(defaults), retainSearchParams<TSearch>([...keys])];
}

/** Page one is index `0`, which the parsers here already default to, so `page=0` is noise in a URL. */
export function pageParam(page: number | undefined): number | undefined {
	return page === 0 ? undefined : page;
}

export function narrowToEnum<T extends string>(
	values: string[] | undefined,
	allowed: readonly T[],
): T[] | undefined {
	if (values === undefined || values.length === 0) {
		return undefined;
	}
	const kept = values.filter((value): value is T => (allowed as readonly string[]).includes(value));
	return kept.length > 0 ? kept : undefined;
}

/**
 * Writing a search param that is UI state on the page you are already on — a filter, a toggle, an
 * open panel — rather than a navigation to somewhere else.
 *
 * The router resets scroll on every commit, including a search-only one
 * ([scroll restoration](https://tanstack.com/router/v1/docs/guide/scroll-restoration)), so a filter
 * chip halfway down a long page throws the reader back to the top. Every such control wants the same
 * option, and the ones that forgot it are indistinguishable from the ones that meant it — so the
 * decision lives here once.
 */
export function useSearchState() {
	const navigate = useNavigate();
	return async (
		update: (previous: Record<string, unknown>) => Record<string, unknown>,
		options?: {
			/**
			 * The history state of the entry written. Left out, the router starts the entry with none,
			 * so a write that must keep what the current entry carries — the marker a drawer level was
			 * pushed with — says `true`, the router's own "keep the current entry's".
			 */
			state?: NonNullable<Parameters<typeof navigate>[0]>["state"];
			/** `replace` rewrites the current history entry: for a change to what an open panel shows, not to which is open. */
			replace?: boolean;
		},
	) => navigate({ to: ".", search: update, resetScroll: false, ...options });
}

/** `useSearchState` for a patch: the keys given replace their current values, the rest stay. */
export function useSearchPatch<TSearch extends object>() {
	const setSearch = useSearchState();
	return (patch: Partial<TSearch>) => {
		void setSearch((previous) => ({ ...previous, ...patch }));
	};
}
