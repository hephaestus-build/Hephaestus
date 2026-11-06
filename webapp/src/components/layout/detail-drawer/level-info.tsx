import type { ReactElement, ReactNode } from "react";

import { InfoTip } from "@/components/common/InfoTip";

/**
 * What a level is for, as `LevelHeader` shows it: the (i) beside the title, and the lines a screen
 * reader hears as the drawer's description. Built by {@link levelInfo} in the level that has one, so
 * `LevelHeader` itself imports no tip and the drawers without one do not load it.
 */
export interface LevelInfo {
	lead: string;
	content?: ReactNode;
	tip: ReactElement;
}

/** A level's {@link LevelInfo}: the answer in one sentence (`lead`) and at most a short line more. */
export function levelInfo({
	label,
	lead,
	content,
}: {
	label: string;
	lead: string;
	content?: ReactNode;
}): LevelInfo {
	return {
		lead,
		content,
		tip: (
			<InfoTip label={label} lead={lead} className="-ml-1.5 self-center">
				{content}
			</InfoTip>
		),
	};
}
