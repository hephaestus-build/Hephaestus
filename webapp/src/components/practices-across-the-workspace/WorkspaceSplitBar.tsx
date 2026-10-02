import { TriangleIcon } from "lucide-react";
import type { ComponentType } from "react";

import { cn } from "cn";
import type { WorkspaceSplit } from "@/api/types.gen";
import { statusToneClass } from "@/components/common/status-def";
import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
} from "@/components/practice-vocabulary/practice-group-standing-defs";

import {
	heldBackSentence,
	isSplitStanding,
	SPLIT_STANDINGS,
	type SplitContext,
	splitDescription,
	standingLabel,
} from "./across-workspace-copy";

export interface WorkspaceSplitBarProps extends SplitContext {
	split: WorkspaceSplit;
	/** The reader's own standing in the group or the practice, shown in every shape. */
	yourStanding: PracticeGroupStandingValue;
}

interface Part {
	key: string;
	count: number;
	label: string;
	icon?: ComponentType<{ className?: string }>;
	/** The ground of the part's piece of the bar. */
	className: string;
	/** Whether the reader is one of the developers this part counts. */
	isYours: boolean;
}

const SPLIT_FIELDS = {
	DEVELOPING: "needsAttention",
	MIXED: "mixedFeedback",
	STRENGTH: "goingWell",
} as const;

function partsOf(
	split: WorkspaceSplit,
	yourStanding: PracticeGroupStandingValue,
	readerCounted: boolean,
): Part[] {
	if (split.shape === "COLLAPSED") {
		const has = isSplitStanding(yourStanding);
		return [
			{
				key: "has",
				count: split.hasStanding ?? 0,
				label: "Have a standing",
				className: "bg-muted-foreground",
				isYours: readerCounted && has,
			},
			{
				key: "none",
				count: split.noneYet ?? 0,
				label: "None yet",
				className: "bg-muted ring-1 ring-border ring-inset",
				isYours: readerCounted && !has,
			},
		];
	}
	return SPLIT_STANDINGS.map((standing) => {
		const def = PRACTICE_GROUP_STANDING_DEFS[standing];
		return {
			key: standing,
			count: split[SPLIT_FIELDS[standing]] ?? 0,
			label: def.label,
			icon: def.icon,
			// The status colour reaches the bar through the registry's tone, never written here.
			className: cn("bg-current", statusToneClass(def.badgeVariant)),
			isYours: readerCounted && standing === yourStanding,
		};
	});
}

/**
 * How the observed developers split across one practice group or one practice, as one segmented
 * bar counted in developers: Needs attention, Mixed feedback and Going well, or, collapsed, has a
 * standing against none yet. Each part carries its count under it, with the standing's icon so
 * the parts never rest on colour alone, and the You marker sits over the reader's own part. Where
 * the marker cannot say the reader's standing, on a collapsed split, a held back one or a reader
 * outside the counts, the word stands under the bar instead.
 */
export function WorkspaceSplitBar({ split, yourStanding, ...context }: WorkspaceSplitBarProps) {
	const description = splitDescription(split, yourStanding, context);
	if (split.shape === "WITHHELD") {
		return (
			<div className="flex min-w-0 flex-col gap-1 text-xs text-muted-foreground">
				<span className="sr-only">{description}</span>
				<p aria-hidden>{heldBackSentence(context)}</p>
				<YourWord standing={yourStanding} />
			</div>
		);
	}
	const parts = partsOf(split, yourStanding, context.readerCounted).filter(
		(part) => part.count > 0,
	);
	const marked = parts.some((part) => part.isYours);
	return (
		<div className="flex min-w-0 flex-col gap-1">
			<div role="img" aria-label={description} className="flex w-full min-w-0 gap-0.5">
				{parts.map((part) => {
					const Icon = part.icon;
					return (
						<div
							key={part.key}
							aria-hidden
							title={`${part.label}: ${part.count}`}
							className="flex min-w-9 grow-(--count) basis-0 flex-col items-center gap-0.5"
							style={{ "--count": part.count }}
						>
							<span className="flex h-5 flex-col items-center justify-end text-mentor">
								{part.isYours && (
									<>
										<span className="text-xs leading-3 font-semibold">You</span>
										<TriangleIcon className="size-2.5 rotate-180 fill-current" />
									</>
								)}
							</span>
							<span className={cn("h-3 w-full rounded-sm", part.className)} />
							<span className="inline-flex items-center gap-1 text-xs text-muted-foreground tabular-nums">
								{Icon && <Icon className="size-3 shrink-0" />}
								{part.count}
							</span>
						</div>
					);
				})}
			</div>
			{(split.shape === "COLLAPSED" || !marked) && (
				<p className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
					{split.shape === "COLLAPSED" && (
						<span aria-hidden>
							{split.hasStanding ?? 0} have a standing, {split.noneYet ?? 0} none yet; split held
							back
						</span>
					)}
					<YourWord standing={yourStanding} />
				</p>
			)}
		</div>
	);
}

/** The reader's own standing as a word, where the marker on the bar cannot say it. */
function YourWord({ standing }: { standing: PracticeGroupStandingValue }) {
	const def = PRACTICE_GROUP_STANDING_DEFS[standing];
	const Icon = def.icon;
	return (
		<span aria-hidden className="inline-flex items-center gap-1">
			<span className="font-semibold text-mentor">You:</span>
			<Icon className={cn("size-3 shrink-0", statusToneClass(def.badgeVariant))} />
			<span className="text-foreground">{standingLabel(standing)}</span>
		</span>
	);
}
