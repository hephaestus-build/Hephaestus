import type { PracticeStanding, PracticeTrend, TrendSupport } from "@/api/types.gen";
import { cn } from "@/lib/utils";

import { standingDefs, type StandingScope } from "./practice-group-standing-defs";
import { PracticeTrendChip, type PracticeTrendChipProps } from "./PracticeTrendChip";
import { StatusBadgeWithSentence } from "./StatusTooltip";

export interface StandingBadgeProps {
	standing: PracticeStanding["standing"];
	/** Whose standing this is; the sentence behind the badge is worded for it. */
	scope: StandingScope;
	className?: string;
}

/**
 * A standing as the registry's badge with its sentence behind it: `StatusBadgeWithSentence` for
 * one enum.
 */
export function StandingBadge({ standing, scope, className }: StandingBadgeProps) {
	return <StatusBadgeWithSentence def={standingDefs(scope)[standing]} className={className} />;
}

export interface TrendNoteProps extends Pick<PracticeTrendChipProps, "scope" | "render"> {
	direction?: PracticeTrend["direction"];
	support?: TrendSupport;
	className?: string;
}

/**
 * The trend chip under a badge. Without a comparable stretch — no direction, or one with no
 * evidence behind it — it is the chip for "not enough to compare yet", drawn the same way.
 */
export function TrendNote({ direction, support, scope, render, className }: TrendNoteProps) {
	return (
		<PracticeTrendChip
			{...(direction && support
				? { direction, support }
				: { direction: "INSUFFICIENT_EVIDENCE" as const })}
			scope={scope}
			render={render}
			className={cn("whitespace-nowrap flex-nowrap gap-x-1.5", className)}
		/>
	);
}
