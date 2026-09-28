import { Link, type LinkComponentProps, useSearch } from "@tanstack/react-router";

import { type DetailStackEntry, detailStackKey, openInStack } from "./detail-stack";

export interface DetailStackLinkProps extends Omit<
	LinkComponentProps,
	"to" | "search" | "resetScroll" | "state" | "replace"
> {
	entry: DetailStackEntry;
}

/**
 * A real link to the current route with one more `detail` param, which is what makes this shallow
 * routing rather than hidden state: the row opens in a new tab, copies and reloads. Opening over the
 * current stack rather than a captured one is what lets one component work at every depth; a level
 * already open is closed down to rather than repeated (`openInStack`).
 */
export function DetailStackLink({ entry, ...props }: DetailStackLinkProps) {
	const current = useSearch({ strict: false, select: (search) => toStack(search.detail) });
	const key = detailStackKey(entry);
	const { detail, pushed } = openInStack(current, key);
	// A link to the level already in front changes nothing, so it must not rewrite that entry's mark.
	const inFront = current.at(-1) === key;
	return (
		<Link
			to="."
			search={(previous: Record<string, unknown>) => ({ ...previous, detail })}
			// See `useDetailStack`: marks a level this visit pushed, so its dismiss can go back.
			state={(previous) => (inFront ? previous : { ...previous, detailPush: pushed })}
			replace={inFront}
			// Omitted from the props above too: opening a panel must never move the page underneath it,
			// and a caller that could pass this could reintroduce that.
			resetScroll={false}
			{...props}
		/>
	);
}

function toStack(detail: unknown): string[] {
	if (Array.isArray(detail)) {
		return detail.filter((value) => typeof value === "string");
	}
	return typeof detail === "string" ? [detail] : [];
}
