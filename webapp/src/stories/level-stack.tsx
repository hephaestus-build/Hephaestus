import type { ReactElement } from "react";

import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import {
	DetailDrawerStack,
	type DetailDrawerStackProps,
} from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";

import { Stateful } from "./stateful";

/**
 * One level in a real drawer stack over the page, as the route mounts it. The path's `onClose` is
 * the story's spy and also closes the stack, so a crumb both reports and does what it says.
 */
export function InLevelStack({
	entry,
	path,
	size = "detail",
	children,
}: {
	entry: DetailStackEntry;
	path: LevelPath;
	/** The width the level's production host gives its stack, so a story wraps where the app does. */
	size?: DetailDrawerStackProps["size"];
	children: (level: { nested: boolean; path: LevelPath }) => ReactElement;
}) {
	return (
		<Stateful initial={[entry]}>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					size={size}
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(_entry, level) =>
						children({
							nested: level.nested,
							path: {
								behind: path.behind,
								onClose: (depth) => {
									path.onClose(depth);
									setStack(stack.slice(0, depth));
								},
							},
						})
					}
				</DetailDrawerStack>
			)}
		</Stateful>
	);
}
