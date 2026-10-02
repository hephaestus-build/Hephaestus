import { CircleAlertIcon, CircleCheckIcon, GitPullRequestIcon } from "lucide-react";

import { cn } from "cn";
import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import { statusToneClass } from "@/components/common/status-def";
import { FEEDBACK_STATE_DEFS } from "@/components/practice-vocabulary/feedback-state-defs";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";

import { windowPhrase } from "./across-workspace-copy";
import { WorkspaceTile, WorkspaceTileSkeleton } from "./WorkspaceTile";

export interface WorkspaceTilesProps {
	/** The overview the tiles read; without it they draw their loading shape. */
	overview?: PracticesAcrossWorkspace;
	/** With the workspace turned off a tile shows the reader's own figure and nothing else. */
	showWorkspace: boolean;
}

const TILE_COUNT = 4;

const GRID = "grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3";

const OpenFeedbackIcon = FEEDBACK_STATE_DEFS.open.icon;

/**
 * The reader's figures beside the workspace's middle half, three to a row so the grid has room for
 * more: reviewed work, practices going well and needing attention, and the feedback open now.
 */
export function WorkspaceTiles({ overview, showWorkspace }: WorkspaceTilesProps) {
	if (overview === undefined) {
		return (
			<div>
				<ul aria-busy className={GRID}>
					{Array.from({ length: TILE_COUNT }, (_, index) => (
						<li key={index} className="flex">
							<WorkspaceTileSkeleton />
						</li>
					))}
				</ul>
				<span className="sr-only">Loading the figures</span>
			</div>
		);
	}
	const shared = {
		observedDevelopers: overview.observedDevelopers,
		window: overview.window,
		showWorkspace,
	};
	const of = `of your ${overview.yourPractices} practices`;
	const open = overview.openFeedback.yours;
	return (
		<ul className={GRID}>
			<li className="flex">
				<WorkspaceTile
					{...shared}
					title="Pieces of work reviewed"
					icon={
						<GitPullRequestIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
					}
					figure={overview.reviewedWork}
					qualifier={windowPhrase(overview.window)}
				/>
			</li>
			<li className="flex">
				<WorkspaceTile
					{...shared}
					title="Practices going well"
					icon={
						<CircleCheckIcon
							className={cn(
								"size-4 shrink-0",
								statusToneClass(PRACTICE_GROUP_STANDING_DEFS.STRENGTH.badgeVariant),
							)}
							aria-hidden
						/>
					}
					figure={overview.practicesGoingWell}
					qualifier={of}
				/>
			</li>
			<li className="flex">
				<WorkspaceTile
					{...shared}
					title="Practices needing attention"
					icon={
						<CircleAlertIcon
							className={cn(
								"size-4 shrink-0",
								statusToneClass(PRACTICE_GROUP_STANDING_DEFS.DEVELOPING.badgeVariant),
							)}
							aria-hidden
						/>
					}
					figure={overview.practicesNeedingAttention}
					qualifier={of}
					note="Based on your latest four pieces of reviewed work."
				/>
			</li>
			<li className="flex">
				<WorkspaceTile
					{...shared}
					title="Open feedback"
					icon={<OpenFeedbackIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />}
					figure={overview.openFeedback}
					qualifier={`${open === 1 ? "piece" : "pieces"} open now`}
					note="Counted as your Practice profile shows it open, whatever the range."
				/>
			</li>
		</ul>
	);
}
