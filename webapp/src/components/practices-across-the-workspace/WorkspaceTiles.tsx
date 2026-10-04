import { GitPullRequestIcon } from "lucide-react";

import { cn } from "cn";
import type { PracticesAcrossWorkspace } from "@/api/types.gen";
import { STAT_TILE_GRID } from "@/components/common/StatTile";
import { statusToneClass } from "@/components/common/status-def";
import { FEEDBACK_STATE_DEFS } from "@/components/practice-vocabulary/feedback-state-defs";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";

import { windowPhrase } from "./across-workspace-copy";
import { WorkspaceTile, WorkspaceTileSkeleton } from "./WorkspaceTile";

export interface WorkspaceTilesProps {
	/** The overview the tiles read; without it they draw their loading shape. */
	overview?: PracticesAcrossWorkspace;
}

const TILE_COUNT = 4;

const OpenFeedbackIcon = FEEDBACK_STATE_DEFS.open.icon;
const GoingWellIcon = PRACTICE_GROUP_STANDING_DEFS.STRENGTH.icon;
const NeedsAttentionIcon = PRACTICE_GROUP_STANDING_DEFS.DEVELOPING.icon;

/**
 * The reader's figures beside the workspace's middle half, in the Activity tiles' grid: reviewed
 * work, practices going well and needing attention, and the feedback open now.
 */
export function WorkspaceTiles({ overview }: WorkspaceTilesProps) {
	if (overview === undefined) {
		return (
			<div className="@container">
				<ul aria-busy className={STAT_TILE_GRID}>
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
	const of = `of your ${overview.yourPractices} practices`;
	const open = overview.openFeedback.yours;
	return (
		<div className="@container">
			<ul className={STAT_TILE_GRID}>
				<li className="flex">
					<WorkspaceTile
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
						title="Practices going well"
						icon={
							<GoingWellIcon
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
						title="Practices needing attention"
						icon={
							<NeedsAttentionIcon
								className={cn(
									"size-4 shrink-0",
									statusToneClass(PRACTICE_GROUP_STANDING_DEFS.DEVELOPING.badgeVariant),
								)}
								aria-hidden
							/>
						}
						figure={overview.practicesNeedingAttention}
						qualifier={of}
					/>
				</li>
				<li className="flex">
					<WorkspaceTile
						title="Open feedback"
						icon={
							<OpenFeedbackIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
						}
						figure={overview.openFeedback}
						noneSentence="Most developers here have no open feedback."
						qualifier={`${open === 1 ? "piece" : "pieces"} open now`}
					/>
				</li>
			</ul>
		</div>
	);
}
