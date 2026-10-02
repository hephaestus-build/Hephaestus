import { Link } from "@tanstack/react-router";
import { ArrowLeftRightIcon, ArrowRightIcon, LockIcon } from "lucide-react";

import { cn } from "cn";
import type { WorkspaceGroupSplit } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { practiceGroupLevel } from "@/components/practice-profile/practice-profile-search";
import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";
import { PRACTICE_GROUP_STANDING_DEFS } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

import {
	type AcrossWorkspaceWindow,
	ESTIMATE_STANDINGS,
	type Estimate,
	estimateSentence,
	reachSentence,
	standingLabel,
} from "./across-workspace-copy";
import { WorkspaceSplitBar } from "./WorkspaceSplitBar";

export interface PracticeGroupSplitRowProps {
	workspaceSlug: string;
	group: WorkspaceGroupSplit;
	window: AcrossWorkspaceWindow;
	readerCounted: boolean;
	/** Whether the workspace's split is shown at all; off, the row is the reader's own standing. */
	showWorkspace: boolean;
	/** Whether the row asks before it shows: no estimate yet, with "Ask me first" on. */
	asking: boolean;
	/** What the reader answered, once they have; it never leaves the browser. */
	estimate?: Estimate;
	onEstimate?: (estimate: Estimate) => void;
}

/**
 * One practice group: its name, which opens the reader's own group on their Practice profile, and
 * either the question where the reader thinks they stand or, once answered, their standing as a
 * word beside the workspace's split. The standing is a word in every state, so a split held back
 * or a missing You marker tells the reader nothing the word does not.
 */
export function PracticeGroupSplitRow({
	workspaceSlug,
	group,
	window,
	readerCounted,
	showWorkspace,
	asking,
	estimate,
	onEstimate,
}: PracticeGroupSplitRowProps) {
	const reach = showWorkspace && !asking ? reachSentence(group, window) : undefined;
	return (
		<li
			className={cn(
				"grid gap-3 border-b px-4 py-3 last:border-b-0 md:grid-cols-[minmax(0,18rem)_minmax(0,1fr)] md:gap-6",
				asking && "bg-muted/40",
			)}
		>
			<div className="flex min-w-0 flex-col gap-1">
				<GroupLink workspaceSlug={workspaceSlug} group={group} />
				{asking ? (
					<p className="pl-6 text-xs text-muted-foreground">Shown after you answer</p>
				) : (
					reach !== undefined && <p className="pl-6 text-xs text-muted-foreground">{reach}</p>
				)}
			</div>
			{asking ? (
				<EstimateQuestion groupName={group.groupName} onEstimate={onEstimate} />
			) : (
				<div className="grid min-w-0 gap-x-4 gap-y-1.5 sm:grid-cols-[12rem_minmax(0,1fr)] sm:items-end">
					<YourStanding standing={group.yourStanding} />
					{showWorkspace &&
						(group.shape === "WITHHELD" ? (
							<p className="text-xs text-muted-foreground">
								Not enough developers observed here to compare yet.
							</p>
						) : (
							<WorkspaceSplitBar group={group} window={window} readerCounted={readerCounted} />
						))}
					{estimate !== undefined && (
						<p className="flex items-start gap-1.5 text-sm text-muted-foreground sm:col-span-2">
							<ArrowLeftRightIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden />
							<span>
								{estimateSentence(estimate, group.yourStanding)}{" "}
								<Badge variant="muted">
									{estimate === "SKIPPED" ? "Skipped" : `Your estimate: ${standingLabel(estimate)}`}
								</Badge>
							</span>
						</p>
					)}
				</div>
			)}
		</li>
	);
}

/** The one link per row: the group's name, straight into the reader's own group level. */
function GroupLink({
	workspaceSlug,
	group,
}: {
	workspaceSlug: string;
	group: WorkspaceGroupSplit;
}) {
	const { Icon, pill } = getGroupVisual(group.groupIcon, group.groupColor);
	return (
		<span className="inline-flex min-w-0 items-center gap-2 text-sm font-medium">
			{/* The icon keeps the group's colour; the pill's ground is dropped, as `GroupName` drops it. */}
			<Icon
				className={cn("size-4 shrink-0", pill, "bg-transparent dark:bg-transparent")}
				aria-hidden
			/>
			<InlineLink
				render={
					<Link
						to="/w/$workspaceSlug/practice-profile"
						params={{ workspaceSlug }}
						search={detailSearch(practiceGroupLevel(group.groupSlug))}
					/>
				}
				aria-label={`${group.groupName}, open your own group on your Practice profile`}
				className="group/link inline-flex items-center gap-1.5"
			>
				{group.groupName}
				<ArrowRightIcon
					className="size-3.5 shrink-0 text-muted-foreground group-hover/link:text-mentor"
					aria-hidden
				/>
			</InlineLink>
		</span>
	);
}

function YourStanding({ standing }: { standing: WorkspaceGroupSplit["yourStanding"] }) {
	const def = PRACTICE_GROUP_STANDING_DEFS[standing];
	const Icon = def.icon;
	return (
		<p className="inline-flex items-center gap-1.5 text-sm font-medium">
			<span className="text-xs font-semibold text-mentor">You:</span>
			<Icon className={cn("size-3.5 shrink-0", statusToneClass(def.badgeVariant))} aria-hidden />
			<span>{def.label}</span>
		</p>
	);
}

/**
 * The question in the row, shaped like Apple Health's State of Mind log: one question, three large
 * answers in the registry's words and icons, and a way out. One press answers it.
 */
function EstimateQuestion({
	groupName,
	onEstimate,
}: {
	groupName: string;
	onEstimate?: (estimate: Estimate) => void;
}) {
	return (
		<div role="group" aria-label={`Your estimate for ${groupName}`} className="flex flex-col gap-2">
			<p className="text-sm font-medium">Where do you think you stand in {groupName}?</p>
			<div className="flex flex-wrap items-center gap-2">
				{ESTIMATE_STANDINGS.map((standing) => {
					const def = PRACTICE_GROUP_STANDING_DEFS[standing];
					const Icon = def.icon;
					return (
						<Button
							key={standing}
							variant="outline"
							shape="pill"
							onClick={() => onEstimate?.(standing)}
						>
							<Icon className={statusToneClass(def.badgeVariant)} aria-hidden />
							{def.label}
						</Button>
					);
				})}
				<Button variant="quiet" onClick={() => onEstimate?.("SKIPPED")}>
					Skip
				</Button>
			</div>
			<p className="flex items-center gap-1 text-xs text-muted-foreground">
				<LockIcon className="size-3 shrink-0" aria-hidden />
				Only you see your estimate.
			</p>
		</div>
	);
}
