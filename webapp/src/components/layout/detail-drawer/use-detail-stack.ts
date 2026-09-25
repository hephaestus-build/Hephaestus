import { useRouter } from "@tanstack/react-router";

import { useSearchState } from "@/lib/search-params";

import { type DetailStackEntry, encodeDetailStack } from "./detail-stack";

/** Values for the level params the hook was given, and no others: a misspelt key does not compile. */
type LevelSearch<TParam extends string> = Partial<Record<TParam, unknown>>;

export interface DetailStackControls<TParam extends string = never> {
	/** Prefer {@link import("./DetailStackLink").DetailStackLink}; this is for openers that cannot be links. */
	open: (entry: DetailStackEntry) => void;
	/**
	 * Opens several levels, one history entry each, so Back closes them one at a time; `levelSearch`
	 * is written with the last of them.
	 */
	push: (entries: DetailStackEntry[], levelSearch?: LevelSearch<TParam>) => Promise<void>;
	close: (depth: number) => void;
}

export interface DetailStackOptions<TParam extends string = never> {
	/**
	 * Search params that mean something only inside a level — an open card, a tab — cleared in the
	 * same write that changes the stack. Going back restores the entry before the level was pushed,
	 * which never held them; a forward write would otherwise leave them dangling under a level
	 * that no longer shows them, or hand them to the next level opened.
	 */
	levelParams?: readonly TParam[];
}

/**
 * Dismissing the top level goes *back*, not forward to a shorter URL, so Escape and the browser's
 * Back button agree — otherwise Escape then Back re-opens what was just dismissed. Only for a level
 * this visit pushed, which is why the history entry is stamped rather than counted: a deep-linked
 * stack has nothing behind it, and going back would leave the app.
 */
export function useDetailStack<TParam extends string = never>(
	stack: DetailStackEntry[],
	{ levelParams = [] }: DetailStackOptions<TParam> = {},
): DetailStackControls<TParam> {
	const setSearch = useSearchState();
	const router = useRouter();

	const goToStack = async (
		next: DetailStackEntry[],
		detailPush: boolean,
		levelSearch: LevelSearch<TParam> = {},
	) =>
		setSearch(
			(previous) => ({
				...previous,
				...Object.fromEntries(levelParams.map((param) => [param, undefined])),
				...levelSearch,
				detail: encodeDetailStack(next),
			}),
			{ state: (previous) => ({ ...previous, detailPush }) },
		);

	const push = async (entries: DetailStackEntry[], levelSearch?: LevelSearch<TParam>) => {
		// In turn, since each entry is written against the location the one before it produced.
		for (const [index, entry] of entries.entries()) {
			await goToStack(
				[...stack, ...entries.slice(0, index), entry],
				true,
				index === entries.length - 1 ? levelSearch : undefined,
			);
		}
	};

	return {
		open: (entry) => {
			void push([entry]);
		},
		push,
		close: (depth) => {
			// Only the top level: the entries below it are not known to be ours.
			if (router.state.location.state.detailPush === true && depth === stack.length - 1) {
				router.history.back();
				return;
			}
			void goToStack(stack.slice(0, depth), false);
		},
	};
}
