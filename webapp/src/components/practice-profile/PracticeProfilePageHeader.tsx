import { ArrowRightIcon, ChevronRightIcon, HistoryIcon } from "lucide-react";

import { cn } from "cn";
import type { ReviewRunRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { countsTogether } from "@/components/practice-vocabulary/feedback-text";
import {
	REVIEW_RUN_STATE_DEFS,
	runStateSpinClass,
} from "@/components/practice-vocabulary/review-run-state-defs";
import type { StandingCounts } from "@/components/practice-vocabulary/standing-counts";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate, formatDayTime } from "@/lib/dates";

import { ALL_PRACTICE_GROUPS_LEVEL } from "./practice-profile-search";

export interface PracticeProfilePageHeaderProps {
	/**
	 * The chip beside the title: when the latest review ran and on what; omitted while no review has
	 * run.
	 */
	latestRun?: ReviewRunRef;
	/** How many practices sit at each standing; the ring and the legend are drawn from these. */
	counts: StandingCounts;
	/**
	 * Every workspace practice has a standing, reviewed or not, so zero here is a workspace with no
	 * practices.
	 */
	practiceCount: number;
	groupCount: number;
	/**
	 * Opens the level with every review of the reader's work; without it the chip is what it used to
	 * be, a statement rather than a door.
	 */
	onOpenRuns?: () => void;
	isLoading?: boolean;
}

/**
 * What a first visit needs to know, in the page's own voice: what is reviewed and how a piece of
 * feedback ends. Everything below the fold is the record this sentence introduces.
 */
const INTRO =
	"Hephaestus reviews your work against the practices your workspace cares about. What it observes lands here, and a piece of feedback resolves once your work comes back clean.";

export function PracticeProfilePageHeader({
	latestRun,
	counts,
	practiceCount,
	groupCount,
	onOpenRuns,
	isLoading = false,
}: PracticeProfilePageHeaderProps) {
	return (
		<header className="flex flex-col gap-3">
			<div className="flex min-w-0 flex-col gap-2">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
					<h1 className="text-2xl font-semibold tracking-tight">Practice profile</h1>
					{isLoading ? (
						<Skeleton className="h-5 w-64 rounded-full" />
					) : (
						<LatestRunChip run={latestRun} onOpenRuns={onOpenRuns} />
					)}
				</div>
				<p className="max-w-2xl text-sm text-muted-foreground">{INTRO}</p>
			</div>
			{/* One link names the destination and covers the card with a pseudo-element, so the whole
			    card is the pointer path while the words stay the keyboard path. The words turn mentor
			    blue while the card is hovered, as every inline link on the practice surfaces does on
			    its own hover. */}
			<StandingSummaryBox
				layout="fill"
				label={
					practiceCount === 0 ? "No practices set up yet" : countLabel(practiceCount, groupCount)
				}
				counts={counts}
				isLoading={isLoading}
				className="group/panel relative transition-colors hover:bg-sidebar"
			>
				<InlineLink
					render={<DetailStackLink entry={ALL_PRACTICE_GROUPS_LEVEL} />}
					className="inline-flex w-full items-center gap-1.5 text-sm font-medium whitespace-nowrap group-hover/panel:text-mentor group-hover/panel:underline after:absolute after:inset-0 sm:w-auto sm:shrink-0 sm:self-center"
				>
					See all practice groups
					<ArrowRightIcon
						className="size-3.5 shrink-0 transition-transform motion-safe:group-hover/panel:translate-x-0.5"
						aria-hidden
					/>
				</InlineLink>
			</StandingSummaryBox>
		</header>
	);
}

interface LatestRunChipProps {
	/** The newest run on the reader's work; absent until one has run. */
	run?: ReviewRunRef;
	onOpenRuns?: () => void;
}

/**
 * The chip beside the title: what the newest review did, and the way into every review of the
 * reader's work. The work's own link is not here — the chip is one control, and a link inside it
 * would be a control inside a control; the run's level names the work and links it.
 *
 * With no run there is nothing to open, so the chip is a plain badge rather than a door onto an
 * empty list. A run still going says so in words and not in a spinner: a review takes minutes, and
 * a spinner that turns for minutes is the lying spinner the loading rules forbid.
 */
function LatestRunChip({ run, onOpenRuns }: LatestRunChipProps) {
	if (run === undefined) {
		return (
			<Badge variant="muted">
				<HistoryIcon aria-hidden />
				No review yet
			</Badge>
		);
	}
	const at = asDate(run.at);
	const { label } = run.reviewedWork;
	const state = run.status === undefined ? undefined : REVIEW_RUN_STATE_DEFS[run.status];
	const running = run.status === "IN_PROGRESS";
	const Icon = running && state ? state.icon : HistoryIcon;
	const iconTone = cn(
		running && state ? statusToneClass(state.badgeVariant) : "text-muted-foreground",
		runStateSpinClass(run.status),
	);

	const words = running ? (
		<>
			<span className="font-semibold">Review running</span>
			<Separator />
			<span className="min-w-0 truncate font-normal text-muted-foreground">on {label}</span>
		</>
	) : (
		<>
			<span className="font-semibold">Latest run</span>
			{at && (
				<time dateTime={at.toISOString()} className="font-normal text-muted-foreground">
					{formatDayTime(at)}
				</time>
			)}
			<Separator />
			<span className="min-w-0 truncate font-normal text-muted-foreground">
				{run.status === "FAILED" ? "stopped before it finished" : `after ${label}`}
			</span>
		</>
	);

	if (!onOpenRuns) {
		return (
			<Badge variant="outline" className="max-w-full">
				<Icon className={iconTone} aria-hidden />
				{words}
			</Badge>
		);
	}
	return (
		<Button
			type="button"
			variant="outline"
			size="xs"
			shape="pill"
			className="max-w-full font-normal"
			onClick={onOpenRuns}
		>
			<Icon className={iconTone} aria-hidden />
			{words}
			<ChevronRightIcon data-icon="inline-end" aria-hidden />
		</Button>
	);
}

/** The hairline between the chip's clauses; a word of its own to a screen reader it is not. */
function Separator() {
	return (
		<span className="text-border" aria-hidden>
			·
		</span>
	);
}

/** "16 practices in 5 groups": one clause, so both counts are digits as soon as either is. */
function countLabel(practiceCount: number, groupCount: number): string {
	return countsTogether([
		{ n: practiceCount, one: "practice", many: "practices" },
		{ n: groupCount, one: "group", many: "groups" },
	]).join(" in ");
}
