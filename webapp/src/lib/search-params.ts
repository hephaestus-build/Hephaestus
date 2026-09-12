import { useNavigate } from "@tanstack/react-router";
import { z } from "zod";

export const multiValue = z
	.union([z.string().transform((value) => [value]), z.array(z.string())])
	.transform((values) => [...new Set(values)])
	.optional()
	.catch(undefined);

export function nonEmpty<T>(values: T[]): T[] | undefined {
	return values.length > 0 ? values : undefined;
}

/** Page one is index `0`, which the parsers here already default to, so `page=0` is noise in a URL. */
export function pageParam(page: number | undefined): number | undefined {
	return page === 0 ? undefined : page;
}

export function narrowToEnum<T extends string>(
	values: string[] | undefined,
	allowed: readonly T[],
): T[] | undefined {
	if (!values?.length) return undefined;
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
	return (
		update: (previous: Record<string, unknown>) => Record<string, unknown>,
		state?: NonNullable<Parameters<typeof navigate>[0]>["state"],
	) => navigate({ to: ".", search: update, state, resetScroll: false });
}

/** `useSearchState` for a patch: the keys given replace their current values, the rest stay. */
export function useSearchPatch<TSearch extends object>() {
	const setSearch = useSearchState();
	return (patch: Partial<TSearch>) => void setSearch((previous) => ({ ...previous, ...patch }));
}
