import { CircleAlertIcon, CircleCheckIcon, GitPullRequestIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { PracticesAcrossWorkspace, WorkspaceTile } from "@/api/types.gen";
import { statusToneClass } from "@/components/common/status-def";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { windowPhrase } from "./across-workspace-copy";

export interface WorkspaceTilesProps {
	/** The overview the tiles read; without it they draw their loading shape. */
	overview?: PracticesAcrossWorkspace;
	/** With the workspace turned off a tile shows the reader's own figure and nothing else. */
	showWorkspace: boolean;
}

/**
 * Three figures, each the reader's own first and the workspace's middle half after, as Apple
 * Health's highlights set a value against "your typical range". There is no tile for feedback: how
 * feedback closes is a read model per card, and a count over it would be a second truth.
 */
export function WorkspaceTiles({ overview, showWorkspace }: WorkspaceTilesProps) {
	if (overview === undefined) {
		return (
			<div>
				<ul aria-busy className="grid grid-cols-1 gap-3 sm:grid-cols-3">
					{[0, 1, 2].map((index) => (
						<li key={index} className="flex">
							<Card size="sm" className="w-full" aria-hidden>
								<CardHeader>
									<Skeleton className="h-4 w-36" />
								</CardHeader>
								<CardContent className="flex flex-col gap-2">
									<Skeleton className="h-7 w-20" />
									<Skeleton className="h-4 w-44" />
								</CardContent>
							</Card>
						</li>
					))}
				</ul>
				<span className="sr-only">Loading the figures</span>
			</div>
		);
	}
	const of = `of your ${overview.yourPractices} practices`;
	return (
		<ul className="grid grid-cols-1 gap-3 sm:grid-cols-3">
			<Tile
				title="Pieces of work reviewed"
				icon={<GitPullRequestIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />}
				tile={overview.reviewedWork}
				qualifier={windowPhrase(overview.window)}
				showWorkspace={showWorkspace}
			/>
			<Tile
				title="Practices going well"
				icon={
					<CircleCheckIcon
						className={`size-4 shrink-0 ${statusToneClass(PRACTICE_GROUP_STANDING_DEFS.STRENGTH.badgeVariant)}`}
						aria-hidden
					/>
				}
				tile={overview.practicesGoingWell}
				qualifier={of}
				showWorkspace={showWorkspace}
			/>
			<Tile
				title="Practices needing attention"
				icon={
					<CircleAlertIcon
						className={`size-4 shrink-0 ${statusToneClass(PRACTICE_GROUP_STANDING_DEFS.DEVELOPING.badgeVariant)}`}
						aria-hidden
					/>
				}
				tile={overview.practicesNeedingAttention}
				qualifier={of}
				note="Based on your latest four pieces of reviewed work."
				showWorkspace={showWorkspace}
			/>
		</ul>
	);
}

function Tile({
	title,
	icon,
	tile,
	qualifier,
	note,
	showWorkspace,
}: {
	title: string;
	icon: ReactNode;
	tile: WorkspaceTile;
	qualifier: string;
	note?: string;
	showWorkspace: boolean;
}) {
	return (
		<li className="flex">
			<Card size="sm" className="w-full">
				<CardHeader>
					<CardTitle className="flex items-center gap-2 text-sm font-medium">
						{icon}
						{title}
					</CardTitle>
				</CardHeader>
				<CardContent className="flex flex-1 flex-col gap-1.5">
					<p className="flex items-baseline gap-1.5">
						<span className="text-2xl leading-none font-semibold tabular-nums">{tile.yours}</span>
						<span className="text-sm text-muted-foreground">{qualifier}</span>
					</p>
					{note !== undefined && <p className="text-xs text-muted-foreground">{note}</p>}
					{showWorkspace && (
						<p className="mt-auto pt-1 text-sm text-muted-foreground">
							{typeof tile.middleLow === "number" && typeof tile.middleHigh === "number" ? (
								<>
									Most developers here have{" "}
									<span className="font-semibold text-foreground tabular-nums">
										{tile.middleLow === tile.middleHigh
											? tile.middleLow
											: `${tile.middleLow} to ${tile.middleHigh}`}
									</span>
									.
								</>
							) : (
								"Needs more data before the workspace shows here."
							)}
						</p>
					)}
				</CardContent>
			</Card>
		</li>
	);
}
