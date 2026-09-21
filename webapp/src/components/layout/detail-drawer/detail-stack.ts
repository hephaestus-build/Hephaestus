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

export function encodeDetailStack(entries: DetailStackEntry[]): string[] | undefined {
	return entries.length > 0 ? entries.map(detailStackKey) : undefined;
}

export function detailStackKey(entry: DetailStackEntry): string {
	return `${entry.kind}:${entry.id}`;
}
