import { Link, type LinkComponentProps } from "@tanstack/react-router";

import { type DetailStackEntry, detailStackKey } from "./detail-stack";

export interface DetailStackLinkProps extends Omit<
	LinkComponentProps,
	"to" | "search" | "resetScroll"
> {
	entry: DetailStackEntry;
}

/**
 * A real link to the current route with one more `detail` param, which is what makes this shallow
 * routing rather than hidden state: the row opens in a new tab, copies and reloads. Appending to
 * `previous` rather than to a captured stack is what lets one component work at every depth.
 */
export function DetailStackLink({ entry, ...props }: DetailStackLinkProps) {
	return (
		<Link
			to="."
			search={(previous: Record<string, unknown>) => ({
				...previous,
				detail: [...toStack(previous.detail), detailStackKey(entry)],
			})}
			// See `useDetailStack`: marks the entry as this visit's, so a dismiss can go back.
			state={(previous) => ({ ...previous, detailPush: true })}
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
