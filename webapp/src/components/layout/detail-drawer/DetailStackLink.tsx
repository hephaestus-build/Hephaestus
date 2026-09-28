import { Link, type LinkComponentProps, useSearch } from "@tanstack/react-router";

import {
	type DetailStackEntry,
	detailStackKey,
	type OpenInStackOptions,
	openInStack,
	stackInSearch,
} from "./detail-stack";

export interface DetailStackLinkProps
	extends
		Omit<LinkComponentProps, "to" | "search" | "resetScroll" | "state" | "replace">,
		OpenInStackOptions {
	entry: DetailStackEntry;
	/**
	 * Search params that say how the level was opened — as a queue, say — written in the same step,
	 * so the level reads them from its own address.
	 */
	levelSearch?: Record<string, unknown>;
}

/**
 * A real link to the current route with one more `detail` param, which is what makes this shallow
 * routing rather than hidden state: the row opens in a new tab, copies and reloads. Opening over the
 * current stack rather than a captured one is what lets one component work at every depth; a level
 * already open is closed down to rather than repeated (`openInStack`).
 */
export function DetailStackLink({
	entry,
	swap = false,
	levelSearch,
	...props
}: DetailStackLinkProps) {
	const current = useSearch({ strict: false, select: (search) => stackInSearch(search.detail) });
	const key = detailStackKey(entry);
	const { detail, pushed } = openInStack(current, key, { swap });
	// A link to the level already in front changes nothing, so it must not rewrite that entry's mark;
	// nor does a swap, which stands in for the level it replaces (`OpenInStackOptions.swap`).
	const keepsEntry = current.at(-1) === key || swap;
	return (
		<Link
			to="."
			search={(previous: Record<string, unknown>) => ({ ...previous, ...levelSearch, detail })}
			// See `useDetailStack`: marks a level this visit pushed, so its dismiss can go back.
			state={(previous) => (keepsEntry ? previous : { ...previous, detailPush: pushed })}
			replace={keepsEntry}
			// Omitted from the props above too: opening a panel must never move the page underneath it,
			// and a caller that could pass this could reintroduce that.
			resetScroll={false}
			{...props}
		/>
	);
}
