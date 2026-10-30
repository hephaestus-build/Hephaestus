import { ArrowRightIcon, ChevronRightIcon, HistoryIcon } from "lucide-react";

import { cn } from "cn";

import type { ProfileReviewRef } from "@/api/types.gen";
import { DotSeparator } from "@/components/common/DotSeparator";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { useSectionOpen } from "@/components/layout/section-folds";
import { countsTogether } from "@/components/practice-vocabulary/feedback-text";
import {
	isActiveReview,
	OWN_REVIEW_STATUS_DEFS,
} from "@/components/practice-vocabulary/review-status-defs";
import type { StandingCounts } from "@/components/practice-vocabulary/standing-counts";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate, formatDayTime } from "@/lib/dates";

import { ALL_PRACTICE_GROUPS_LEVEL, REVIEWS_LEVEL } from "./practice-profile-search";
import { PracticeProfileGuide } from "./PracticeProfileGuide";

export interface PracticeProfilePageHeaderProps {
	/**
	 * The newest review of the reader's work, which the chip beside the title describes and which
	 * opens the reviews of their work; absent while none has run, when the chip says so instead.
	 */
	latestReview?: ProfileReviewRef;
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
 * What a first visit needs to know, in the page's own voice: what is reviewed. How a piece of
 * feedback ends is in "How to read this page", under it. Everything below the fold is the record
 * this sentence introduces.
 */
const INTRO =
	"Hephaestus reviews your work against the practices your workspace cares about. What it records appears here.";

/**
 * Where this browser keeps whether the reader closed "How to read this page". It opens on a first
 * visit and stays as the reader left it.
 */
export const GUIDE_FOLD_KEY = "practice-profile:how-to-read";

export function PracticeProfilePageHeader({
	latestReview,
	counts,
	practiceCount,
	groupCount,
	isLoading = false,
}: PracticeProfilePageHeaderProps) {
	const [guideOpen, setGuideOpen] = useSectionOpen(GUIDE_FOLD_KEY);
	return (
		<header className="flex flex-col gap-3">
			<div className="flex min-w-0 flex-col gap-2">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
					<h1 className="text-2xl font-semibold tracking-tight">Practice profile</h1>
					{isLoading ? (
						<Skeleton className="h-5 w-64 rounded-full" />
					) : (
						<LatestReviewChip review={latestReview} />
					)}
				</div>
				<p className="max-w-2xl text-sm text-muted-foreground">{INTRO}</p>
			</div>
			<PracticeProfileGuide open={guideOpen} onOpenChange={setGuideOpen} />
			{/* One link names the destination and covers the card with a pseudo-element, so the whole
			    card is the pointer path while the words stay the keyboard path. The words turn highlight
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
					className="inline-flex w-full items-center gap-1.5 text-sm font-medium whitespace-nowrap group-hover/panel:text-highlight group-hover/panel:underline after:absolute after:inset-0 sm:w-auto sm:shrink-0 sm:self-center"
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

const { RUNNING } = OWN_REVIEW_STATUS_DEFS;

interface LatestReviewChipProps {
	/** The newest review of the reader's work; absent until one has run. */
	review?: ProfileReviewRef;
}

/**
 * What the newest review did, and the way into the reviews of the reader's work. The work is not
 * linked here: a link inside the chip would be a control inside a control. A review still going
 * says so in words; its icon stands still, as every status icon does.
 */
function LatestReviewChip({ review }: LatestReviewChipProps) {
	const today = new Date(useNow());
	if (review === undefined) {
		return (
			<Badge variant="muted">
				<HistoryIcon aria-hidden />
				No review yet
			</Badge>
		);
	}
	const at = asDate(review.at);
	const { label } = review.item;
	const running = review.status !== null && isActiveReview(review.status);
	const stopped = review.status === "FAILED" || review.status === "CANCELLED";
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
				{stopped ? "stopped before it finished" : `after ${label}`}
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
	return <DotSeparator className="text-border" />;
}

/** "16 practices in 5 groups": one clause, so both counts are digits as soon as either is. */
function countLabel(practiceCount: number, groupCount: number): string {
	return countsTogether([
		{ n: practiceCount, one: "practice", many: "practices" },
		{ n: groupCount, one: "group", many: "groups" },
	]).join(" in ");
}
