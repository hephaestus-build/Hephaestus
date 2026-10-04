import { GitPullRequestIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type {
	PracticesAcrossWorkspaceTiles,
	WorkspaceTile as WorkspaceTileFigure,
} from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import { STAT_TILE_GRID } from "@/components/common/StatTile";
import { statusToneClass } from "@/components/common/status-def";
import { FEEDBACK_STATE_DEFS } from "@/components/practice-vocabulary/feedback-state-defs";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";

import { windowPhrase } from "./across-workspace-copy";
import { WorkspaceTile, WorkspaceTileSkeleton } from "./WorkspaceTile";

export interface WorkspaceTilesProps {
	/** The window's tiles; without them those tiles draw their loading shape. */
	tiles?: PracticesAcrossWorkspaceTiles;
	/** The open feedback, which reads no window; without it its tile draws its loading shape. */
	openFeedback?: WorkspaceTileFigure;
	/** While another window's tiles are on their way and the ones shown are the previous window's. */
	stale?: boolean;
}

interface TileRead {
	figure: WorkspaceTileFigure;
	qualifier: string;
	noneSentence?: string;
}

interface TileDef {
	key: string;
	title: string;
	icon: ReactNode;
	/** Whether the window moves the tile, so the previous window's figure is drained while it is stale. */
	windowed: boolean;
	/** The tile's figure, once the read it comes from is in. */
	read: (props: WorkspaceTilesProps) => TileRead | undefined;
}

const OpenFeedbackIcon = FEEDBACK_STATE_DEFS.open.icon;
const GoingWellIcon = PRACTICE_GROUP_STANDING_DEFS.STRENGTH.icon;
const NeedsAttentionIcon = PRACTICE_GROUP_STANDING_DEFS.DEVELOPING.icon;

const ofYourPractices = (tiles: PracticesAcrossWorkspaceTiles) =>
	`of your ${tiles.yourPractices} practices`;

/** Every tile in the order the grid lays them out, the one home of how many there are. */
const TILE_DEFS: readonly TileDef[] = [
	{
		key: "reviewed-work",
		title: "Pieces of work reviewed",
		icon: <GitPullRequestIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />,
		windowed: true,
		read: ({ tiles }) =>
			tiles && { figure: tiles.reviewedWork, qualifier: windowPhrase(tiles.window) },
	},
	{
		key: "going-well",
		title: PRACTICE_GROUP_STANDING_DEFS.STRENGTH.practicesTitle,
		icon: (
			<GoingWellIcon
				className={cn(
					"size-4 shrink-0",
					statusToneClass(PRACTICE_GROUP_STANDING_DEFS.STRENGTH.badgeVariant),
				)}
				aria-hidden
			/>
		),
		windowed: true,
		read: ({ tiles }) =>
			tiles && { figure: tiles.practicesGoingWell, qualifier: ofYourPractices(tiles) },
	},
	{
		key: "needing-attention",
		title: PRACTICE_GROUP_STANDING_DEFS.DEVELOPING.practicesTitle,
		icon: (
			<NeedsAttentionIcon
				className={cn(
					"size-4 shrink-0",
					statusToneClass(PRACTICE_GROUP_STANDING_DEFS.DEVELOPING.badgeVariant),
				)}
				aria-hidden
			/>
		),
		windowed: true,
		read: ({ tiles }) =>
			tiles && { figure: tiles.practicesNeedingAttention, qualifier: ofYourPractices(tiles) },
	},
	{
		key: "open-feedback",
		title: "Open feedback",
		icon: <OpenFeedbackIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />,
		windowed: false,
		read: ({ openFeedback }) =>
			openFeedback && {
				figure: openFeedback,
				noneSentence: "Most developers here have no open feedback.",
				qualifier: `${openFeedback.yours === 1 ? "piece" : "pieces"} open now`,
			},
	},
];

/**
 * The reader's figures beside the workspace's middle half, in the Activity tiles' grid: reviewed
 * work, practices going well and needing attention over the window, and the feedback open now.
 * Each tile draws its loading shape until the read it comes from is in.
 */
export function WorkspaceTiles(props: WorkspaceTilesProps) {
	const stale = props.stale === true;
	const busy = props.tiles === undefined || props.openFeedback === undefined || stale;
	return (
		<div className="@container">
			<ul aria-busy={busy ? true : undefined} className={STAT_TILE_GRID}>
				{TILE_DEFS.map((def) => {
					const read = def.read(props);
					return (
						<li key={def.key} className={cn("flex", def.windowed && stale && STALE)}>
							{read === undefined ? (
								<WorkspaceTileSkeleton />
							) : (
								<WorkspaceTile title={def.title} icon={def.icon} {...read} />
							)}
						</li>
					);
				})}
			</ul>
			{busy && <span className="sr-only">Loading the figures</span>}
		</div>
	);
}
