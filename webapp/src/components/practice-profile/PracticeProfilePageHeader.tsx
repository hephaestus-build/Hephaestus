import { ArrowRightIcon, ChevronRightIcon, HistoryIcon } from "lucide-react";

import { cn } from "cn";

import type { ReviewRunRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { countsTogether } from "@/components/practice-vocabulary/feedback-text";
import { REVIEW_RUN_STATE_DEFS } from "@/components/practice-vocabulary/review-run-state-defs";
import type { StandingCounts } from "@/components/practice-vocabulary/standing-counts";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate, formatDayTime } from "@/lib/dates";

import { ALL_PRACTICE_GROUPS_LEVEL, REVIEWS_LEVEL } from "./practice-profile-search";

export interface PracticeProfilePageHeaderProps {
	/**
	 * The newest review of the reader's work, which the chip beside the title describes and which
	 * opens the reviews of their work; absent while none has run, when the chip says so instead.
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
	isLoading?: boolean;
}

/**
 * What a first visit needs to know, in the page's own voice: what is reviewed and how a piece of
 * feedback ends. Everything below the fold is the record this sentence introduces.
 */
const INTRO =
	"Hephaestus reviews your work against the practices your workspace cares about. What it observes appears here. A piece of feedback resolves once your work comes back clean.";

export function PracticeProfilePageHeader({
	latestRun,
	counts,
	practiceCount,
	groupCount,
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
						<LatestReviewChip run={latestRun} />
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

const RUNNING = REVIEW_RUN_STATE_DEFS.IN_PROGRESS;

interface LatestReviewChipProps {
	/** The newest review of the reader's work; absent until one has run. */
	run?: ReviewRunRef;
}

/**
 * What the newest review did, and the way into the reviews of the reader's work. The work is not
 * linked here: a link inside the chip would be a control inside a control. A review still going
 * says so in words; its icon stands still, as every status icon does.
 */
function LatestReviewChip({ run }: LatestReviewChipProps) {
	const today = new Date(useNow());
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
	const running = run.status === "IN_PROGRESS";
	const Icon = running ? RUNNING.icon : HistoryIcon;
	const iconTone = running ? statusToneClass(RUNNING.badgeVariant) : "text-muted-foreground";

	const words = running ? (
		<>
			<span className="font-semibold">Review running</span>
			<ClauseDot />
			<span className="min-w-0 truncate font-normal text-muted-foreground">on {label}</span>
		</>
	) : (
		<>
			<span className="font-semibold">Latest review</span>
			{at && (
				<time dateTime={at.toISOString()} className="font-normal text-muted-foreground">
					{formatDayTime(at, today)}
				</time>
			)}
			<ClauseDot />
			<span className="min-w-0 truncate font-normal text-muted-foreground">
				{run.status === "FAILED" ? "stopped before it finished" : `after ${label}`}
			</span>
		</>
	);

	return (
		<DetailStackLink
			entry={REVIEWS_LEVEL}
			className={cn(
				buttonVariants({ variant: "outline", size: "xs", shape: "pill" }),
				"max-w-full font-normal",
			)}
		>
			<Icon className={iconTone} aria-hidden />
			{words}
			<ChevronRightIcon data-icon="inline-end" aria-hidden />
		</DetailStackLink>
	);
}

function ClauseDot() {
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
