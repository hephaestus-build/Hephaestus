import type { ItemRef } from "@/api/types.gen";
import { type ItemKindIcon, itemKindIcon, itemKindNoun } from "@/lib/item-kinds";

import { count } from "./feedback-text";

type WorkRef = Pick<ItemRef, "id" | "kind" | "provider">;

/** One piece of work's identity across kinds: an issue and a pull request can share a number. */
export const workKey = (ref: Pick<ItemRef, "id" | "kind">) => `${ref.kind} ${ref.id}`;

/** What a feedback card's head says about the work the pattern recurs in. */
export interface WorkSeenIn<TRef extends WorkRef = WorkRef> {
	/**
	 * "Recurring in three pull requests", "Recurring in one merge request": a word below ten, as the
	 * card's other numbers in prose (`count`).
	 */
	label: string;
	icon: ItemKindIcon;
	/** Each piece of work once, in the order given: what the label counts, by name. */
	pieces: TRef[];
	/** The pieces as a heading names them: "these pull requests", "this merge request". */
	these: string;
}

/**
 * The kind and number of the distinct pieces of work behind a piece of feedback, read off the work
 * itself: a pull request at GitHub, a merge request at GitLab, an issue, a conversation. Work of
 * more than one kind, or of a kind this build does not know, reads as "pieces of reviewed work" or
 * "pieces of work" (`itemKindNoun`), never as a guess. A provider is named only when every piece
 * shares it. No work, no label.
 */
export function workSeenIn<TRef extends WorkRef>(
	refs: readonly TRef[],
): WorkSeenIn<TRef> | undefined {
	const distinct = new Map(refs.map((ref) => [workKey(ref), ref]));
	const pieces = [...distinct.values()];
	if (pieces.length === 0) {
		return undefined;
	}
	const kinds = new Set(pieces.map((piece) => piece.kind));
	const providers = new Set(pieces.map((piece) => piece.provider));
	const [kind] = kinds.size === 1 ? kinds : [undefined];
	const [provider] = providers.size === 1 ? providers : [undefined];
	const noun = itemKindNoun(kind, pieces.length, provider);
	return {
		label: `Recurring in ${count(pieces.length, noun, noun)}`,
		icon: itemKindIcon(kind),
		pieces,
		these: `${pieces.length === 1 ? "this" : "these"} ${noun}`,
	};
}
