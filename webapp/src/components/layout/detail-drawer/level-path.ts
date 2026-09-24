import type { DetailStackEntry } from "./detail-stack";
import type { LevelPath } from "./DetailPath";

export interface LevelPathOptions<TEntry extends DetailStackEntry> {
	/** The covered page, by name: the first crumb, closing down to depth 0. */
	pageLabel: string;
	/** A level behind, by what it shows; `index` is its position in the stack. */
	labelOf: (entry: TEntry, index: number) => string;
	/** `useDetailStack(...).close`. */
	onClose: LevelPath["onClose"];
}

/**
 * The path each level of a stack is handed: the page, then every level below it by name, each crumb
 * closing down to itself. Written once so a surface names its levels and nothing else.
 */
export function levelPathAt<TEntry extends DetailStackEntry>(
	stack: TEntry[],
	{ pageLabel, labelOf, onClose }: LevelPathOptions<TEntry>,
): (depth: number) => LevelPath {
	return (depth) => ({
		behind: [
			{ label: pageLabel, depth: 0 },
			...stack
				.slice(0, depth)
				.map((entry, index) => ({ label: labelOf(entry, index), depth: index + 1 })),
		],
		onClose,
	});
}
