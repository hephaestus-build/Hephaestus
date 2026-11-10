import type { HistoryState } from "@tanstack/react-router";
import { z } from "zod";

import { multiValue } from "@/lib/search-params";

/** Encoded in the URL as `kind:id`; repeated for depth, as `?detail=group:code-review&detail=practice:x`. */
export interface DetailStackEntry<TKind extends string = string> {
	kind: TKind;
	id: string;
}

/**
 * Each level mounts a drawer, a portal and a focus trap, and the URL is untrusted input by design —
 * it is meant to be shared and hand-edited. No surface stacks this deep.
 */
export const DETAIL_STACK_MAX_DEPTH = 4;

/**
 * `multiValue` is what dedupes: the same entry twice is never a stack, and appending is one
 * double-click away from producing it.
 */
export function detailStackSchema(kinds: readonly string[]) {
	const known = new Set<string>(kinds);
	return z.object({
		detail: multiValue.transform((values) =>
			values
				?.filter((value) => {
					const separator = value.indexOf(":");
					return (
						separator > 0 && separator < value.length - 1 && known.has(value.slice(0, separator))
					);
				})
				.slice(0, DETAIL_STACK_MAX_DEPTH),
		),
	});
}

/**
 * The kinds are passed in rather than claimed by a type argument: the URL is untrusted, and a value
 * matched against the caller's own list arrives as that kind rather than as a promise about it.
 */
export function parseDetailStack<TKind extends string>(
	raw: string[] | undefined,
	kinds: readonly TKind[],
): DetailStackEntry<TKind>[] {
	return (raw ?? []).flatMap((value) => {
		const separator = value.indexOf(":");
		const prefix = value.slice(0, separator);
		const kind = kinds.find((candidate) => candidate === prefix);
		return kind === undefined ? [] : [{ kind, id: value.slice(separator + 1) }];
	});
}

export interface OpenInStackOptions {
	/**
	 * Replace the level in front instead of opening one over it — for stepping through a queue of
	 * records, where the next one takes the place of the one just handled. A swap is written over
	 * the current history entry and keeps its mark, so dismissing the level still goes back to where
	 * it was first opened from, and Back does not walk through every record already handled.
	 */
	swap?: boolean;
	/**
	 * The surface shows one level at a time: a link from a level replaces it rather than opening over
	 * it. Unlike `swap`, it is a step of its own in history, so Back returns to the record it replaced;
	 * and it is not a push, so dismissing it closes to the page instead of going back to that record.
	 */
	single?: boolean;
}

/**
 * Reopen an existing level by shortening the stack; replace the top when the stack is full.
 * Only a stack that grows uses push history, so Back cannot reopen levels the link just closed.
 */
export function openInStack(
	stack: readonly string[],
	key: string,
	{ swap = false, single = false }: OpenInStackOptions = {},
): { detail: string[]; pushed: boolean } {
	const open = stack.indexOf(key);
	if (open !== -1) {
		return { detail: stack.slice(0, open + 1), pushed: false };
	}
	if ((swap || single) && stack.length > 0) {
		return { detail: [...stack.slice(0, -1), key], pushed: false };
	}
	if (stack.length >= DETAIL_STACK_MAX_DEPTH) {
		return { detail: [...stack.slice(0, DETAIL_STACK_MAX_DEPTH - 1), key], pushed: false };
	}
	return { detail: [...stack, key], pushed: true };
}

/**
 * The stack in wire form from a search read without the route's schema — `useSearch({ strict: false })`
 * — where a one-level stack is still the bare string the URL carries and a deeper one an array.
 */
export function stackInSearch(detail: unknown): string[] {
	if (Array.isArray(detail)) {
		return detail.filter((value) => typeof value === "string");
	}
	return typeof detail === "string" ? [detail] : [];
}

/** The search that opens `entry` alone, for a link from another route. */
export function detailSearch(entry: DetailStackEntry): { detail: string[] } {
	return { detail: [detailStackKey(entry)] };
}

export function detailStackKey(entry: DetailStackEntry): string {
	return `${entry.kind}:${entry.id}`;
}

/**
 * How many levels at the top of the entry's stack this visit pushed, one history entry each, so
 * that many can be dismissed by going back. An entry stamped before the count was kept counts its
 * own push alone.
 */
export function pushedLevels(state: HistoryState | undefined): number {
	if (state?.detailPush !== true) {
		return 0;
	}
	return state.detailPushes ?? 1;
}

/**
 * The state of a history entry the stack writes. A new entry carries only its own stamp, never a
 * marker another writer left on the entry it came from: such a marker describes that one entry, and
 * copied forward it would make a later writer mistake the reader's own level for its step. An entry
 * rewritten in place keeps what it had.
 *
 * The stamp is whether the entry pushed a level and, if so, how many levels it and the entries
 * right before it pushed in a row ({@link pushedLevels}): read off the entry it came from, not
 * copied from it, so a run of pushes counts up and anything else starts it over.
 */
export function detailEntryState(
	previous: HistoryState,
	detailPush: boolean,
	replace: boolean,
): HistoryState {
	const stamp = { detailPush, detailPushes: detailPush ? pushedLevels(previous) + 1 : undefined };
	return replace ? { ...previous, ...stamp } : stamp;
}
