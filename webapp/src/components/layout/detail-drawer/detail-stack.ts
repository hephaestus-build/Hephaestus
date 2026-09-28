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

/**
 * The stack after opening `key` over `stack`, both in wire form, and whether it grew by one level.
 *
 * A surface whose levels link to each other — feedback to its observation and back — is a graph, so
 * a level can link to one already open below it. Appending would be a repeat the schema drops, and the
 * link would do nothing; closing down to that level is what the reader meant. A full stack swaps its
 * top level instead, so the link still opens what it names.
 *
 * Only a level that grew the stack is a push: {@link import("./use-detail-stack").useDetailStack}
 * dismisses a pushed level by going back, and going back from a shortened or swapped stack would
 * re-open what the link just closed.
 */
export function openInStack(
	stack: readonly string[],
	key: string,
): { detail: string[]; pushed: boolean } {
	const open = stack.indexOf(key);
	if (open !== -1) {
		return { detail: stack.slice(0, open + 1), pushed: false };
	}
	if (stack.length >= DETAIL_STACK_MAX_DEPTH) {
		return { detail: [...stack.slice(0, DETAIL_STACK_MAX_DEPTH - 1), key], pushed: false };
	}
	return { detail: [...stack, key], pushed: true };
}

export function encodeDetailStack(entries: DetailStackEntry[]): string[] | undefined {
	return entries.length > 0 ? entries.map(detailStackKey) : undefined;
}

/** The search that opens `entry` alone, for a link from another route. */
export function detailSearch(entry: DetailStackEntry): { detail: string[] } {
	return { detail: [detailStackKey(entry)] };
}

export function detailStackKey(entry: DetailStackEntry): string {
	return `${entry.kind}:${entry.id}`;
}
