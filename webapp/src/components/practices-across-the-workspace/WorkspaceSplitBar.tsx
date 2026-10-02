import { TriangleIcon } from "lucide-react";

import { cn } from "cn";
import type { WorkspaceGroupSplit } from "@/api/types.gen";
import { statusToneClass } from "@/components/common/status-def";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";

import {
	type AcrossWorkspaceWindow,
	type EstimateStanding,
	isEstimateStanding,
	splitDescription,
} from "./across-workspace-copy";

export interface WorkspaceSplitBarProps {
	/** A group whose split is shown: `SPLIT` or `COLLAPSED`. A withheld group draws no bar. */
	group: WorkspaceGroupSplit;
	window: AcrossWorkspaceWindow;
	/** Whether the reader is inside the counts; without it the bar carries no You marker. */
	readerCounted: boolean;
}

interface Segment {
	key: string;
	count: number;
	label: string;
	className: string;
}

/** The fixed order of the split: what needs attention first, as the practice profile lists it. */
const SPLIT_ORDER: readonly {
	standing: EstimateStanding;
	field: "needsAttention" | "mixedFeedback" | "goingWell";
}[] = [
	{ standing: "DEVELOPING", field: "needsAttention" },
	{ standing: "MIXED", field: "mixedFeedback" },
	{ standing: "STRENGTH", field: "goingWell" },
];

function segmentsOf(group: WorkspaceGroupSplit): Segment[] {
	if (group.shape === "COLLAPSED") {
		return [
			{
				key: "has",
				count: group.hasStanding ?? 0,
				label: "have a standing",
				className: "bg-muted-foreground text-background",
			},
			{
				key: "none",
				count: group.noneYet ?? 0,
				label: "none yet",
				className: "bg-muted text-muted-foreground ring-1 ring-border ring-inset",
			},
		];
	}
	return SPLIT_ORDER.map(({ standing, field }) => {
		const def = PRACTICE_GROUP_STANDING_DEFS[standing];
		return {
			key: standing,
			count: group[field] ?? 0,
			label: def.label,
			// The status colour reaches the bar through the registry's tone, never written here.
			className: cn("bg-current", statusToneClass(def.badgeVariant)),
		};
	});
}

/**
 * The workspace's split of one practice group as one segmented bar, counted in developers, in the
 * fixed order Needs attention, Mixed feedback, Going well. The You marker sits over the middle of the
 * reader's own segment, and only on a split that is shown with the reader inside it: on a collapsed
 * split it would point at a part that says nothing about the reader's standing.
 */
export function WorkspaceSplitBar({ group, window, readerCounted }: WorkspaceSplitBarProps) {
	const segments = segmentsOf(group);
	const total = segments.reduce((sum, segment) => sum + segment.count, 0);
	const yourIndex =
		group.shape === "SPLIT" && readerCounted && isEstimateStanding(group.yourStanding)
			? SPLIT_ORDER.findIndex(({ standing }) => standing === group.yourStanding)
			: -1;
	const before = segments.slice(0, Math.max(yourIndex, 0)).reduce((sum, s) => sum + s.count, 0);
	const yourSegment = segments[yourIndex];
	const pointerAt =
		yourSegment !== undefined && total > 0
			? ((before + yourSegment.count / 2) / total) * 100
			: undefined;

	return (
		<div
			role="img"
			aria-label={splitDescription(group, window, readerCounted)}
			className="flex min-w-0 flex-col gap-0.5"
		>
			<div className="relative h-5" aria-hidden>
				{pointerAt !== undefined && (
					<span
						className="absolute bottom-0 left-(--you-at) flex -translate-x-1/2 flex-col items-center text-mentor"
						style={{ "--you-at": `${pointerAt}%` }}
					>
						<span className="text-xs leading-3 font-semibold">You</span>
						<TriangleIcon className="size-2.5 rotate-180 fill-current" />
					</span>
				)}
			</div>
			<div className="flex h-4 w-full gap-0.5" aria-hidden>
				{segments
					.filter((segment) => segment.count > 0)
					.map((segment) => (
						<span
							key={segment.key}
							title={`${segment.label}: ${segment.count}`}
							className={cn(
								"flex min-w-1 grow-(--count) basis-0 items-center justify-center text-2xs leading-none font-semibold tabular-nums first:rounded-l last:rounded-r",
								segment.className,
							)}
							style={{ "--count": segment.count }}
						>
							<span className={group.shape === "SPLIT" ? "text-background" : undefined}>
								{segment.count}
							</span>
						</span>
					))}
			</div>
			{group.shape === "COLLAPSED" && (
				<span className="text-xs text-muted-foreground" aria-hidden>
					{group.hasStanding ?? 0} have a standing · {group.noneYet ?? 0} none yet · split held back
				</span>
			)}
		</div>
	);
}
