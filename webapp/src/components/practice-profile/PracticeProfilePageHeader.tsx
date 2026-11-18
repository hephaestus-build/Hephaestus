import { ArrowRightIcon, ChevronRightIcon, HistoryIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";

import type { ProfileReviewRef } from "@/api/types.gen";
import { DotSeparator } from "@/components/common/DotSeparator";
import { InfoLines, InfoTip } from "@/components/common/InfoTip";
import { InlineLink } from "@/components/common/InlineLink";
import { statusToneClass } from "@/components/common/status-def";
import { useNow } from "@/components/common/use-now";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { countsTogether } from "@/components/practice-vocabulary/feedback-text";
import {
	isActiveReview,
	OWN_REVIEW_STATUS_DEFS,
	reviewProgressWords,
	shownReviewStatusDef,
} from "@/components/practice-vocabulary/review-status-defs";
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
	latestReview?: ProfileReviewRef;
	/**
	 * A review of the reader's work that is waiting or running and has recorded nothing yet. The chip
	 * names it while no review has recorded anything.
	 */
	activeReview?: ProfileReviewRef;
	/** How many practices sit at each standing; the ring and the legend are drawn from these. */
	counts: StandingCounts;
	/**
	 * Every workspace practice has a standing, reviewed or not, so zero here is a workspace with no
	 * practices.
	 */
	practiceCount: number;
	groupCount: number;
	isLoading?: boolean;
	/** The control that starts the guided tour; absent where the reader cannot take it, such as in a user view. */
	tourAction?: ReactNode;
	/** Whether this workspace shows its admins practice results per person; absent while unknown. */
	perPersonResults?: boolean;
}

export const PRACTICE_PROFILE_TITLE = "Practice profile";

/**
 * What a first visit needs to know, in the page's own voice: what is reviewed. Each part of the page
 * explains itself in an (i) beside it, and the guided tour shows how the parts connect.
 */
export const PRACTICE_PROFILE_INTRO =
	"Hephaestus reviews your work against the practices your workspace cares about. What it records appears here.";

/**
 * Who can see what on the Practice profile. The (i) beside the intro and the guided tour both say it,
 * from here. What workspace admins see depends on the workspace's per-person results setting; while
 * it is unknown, as in the tour, the sentence names the setting instead.
 */
export function profileAudience(perPersonResults?: boolean): string {
	let admins =
		"Workspace admins see practice reviews, with your name only if a workspace owner turned on per-person results.";
	if (perPersonResults === true) {
		admins =
			"In this workspace, admins see your practice results with your name, earlier ones too, because an owner turned on per-person results.";
	} else if (perPersonResults === false) {
		admins =
			"In this workspace, admins see practice results with no name and cannot filter them by person. Each result still shows the work it is about.";
	}
	return `Your Practice profile is yours. Feedback on a pull request is visible to everyone who can see that pull request. ${admins} Instance admins can open a read-only view.`;
}

/** Who can see the page: a fact the reader keeps, so it stays beside the intro (onboarding rule 9). */
const audienceInfo = (perPersonResults?: boolean) => (
	<InfoTip label="Who can see this page">
		<p>{profileAudience(perPersonResults)}</p>
	</InfoTip>
);

/** What the ring and its counts mean, beside them. The registry's sentence for each standing stays on its badge. */
const STANDING_INFO = (
	<InfoTip
		label="About your standing"
		// Over the summary's link, which covers the whole box for a pointer.
		className="relative z-10"
		lead="Your standing on a practice comes from your recent reviewed work. It is not a grade."
	>
		<InfoLines
			lines={[
				{
					term: "The ring",
					text: "counts your practices by standing. Gray means nothing to judge yet.",
				},
				{
					term: "A practice",
					text: "is one way of working that your workspace cares about. Related practices form a practice group.",
				},
			]}
		/>
	</InfoTip>
);

export function PracticeProfilePageHeader({
	latestReview,
	activeReview,
	counts,
	practiceCount,
	groupCount,
	isLoading = false,
	tourAction,
	perPersonResults,
}: PracticeProfilePageHeaderProps) {
	return (
		<header className="flex flex-col gap-3">
			<div className="flex min-w-0 flex-col gap-2">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
					<h1 className="text-2xl font-semibold tracking-tight">{PRACTICE_PROFILE_TITLE}</h1>
					<div data-tour-slot="chip" className="max-w-full min-w-0">
						{isLoading ? (
							<Skeleton className="h-5 w-64 rounded-full" />
						) : (
							<LatestReviewChip review={latestReview} activeReview={activeReview} />
						)}
					</div>
				</div>
				<p className="max-w-2xl text-sm text-muted-foreground">
					{PRACTICE_PROFILE_INTRO} {audienceInfo(perPersonResults)}
				</p>
			</div>
			{/* The action's row holds its place while the page loads, so nothing under it moves when it comes. */}
			{tourAction !== undefined && (
				<div className="-ml-2.5 flex">
					{isLoading ? <Skeleton className="ml-2.5 h-7 w-28" /> : tourAction}
				</div>
			)}
			{/* One link names the destination and covers the card with a pseudo-element, so the whole
			    card is the pointer path while the words stay the keyboard path. The words turn highlight
			    blue while the card is hovered, as every inline link on the practice surfaces does on
			    its own hover. */}
			<div data-tour-slot="ring">
				<StandingSummaryBox
					layout="fill"
					label={
						practiceCount === 0 ? "No practices set up yet" : countLabel(practiceCount, groupCount)
					}
					counts={counts}
					isLoading={isLoading}
					info={STANDING_INFO}
					className="group/panel relative transition-colors hover:bg-sidebar"
				>
					<InlineLink
						data-tour-slot="see-all-groups"
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
			</div>
		</header>
	);
}

const { RUNNING } = OWN_REVIEW_STATUS_DEFS;

interface LatestReviewChipProps {
	/** The newest review of the reader's work; absent until one has run. */
	review?: ProfileReviewRef;
	/** A first review still under way, which has no result to open yet. */
	activeReview?: ProfileReviewRef;
}

/**
 * What the newest review did, and the way into the reviews of the reader's work. The work is not
 * linked here: a link inside the chip would be a control inside a control. A review still going
 * says so in words; its icon stands still, as every status icon does.
 */
function LatestReviewChip({ review, activeReview }: LatestReviewChipProps) {
	const today = new Date(useNow());
	if (review === undefined && activeReview !== undefined) {
		// Not a link: the reviews of the reader's work list a review once it records something.
		const def = shownReviewStatusDef(
			OWN_REVIEW_STATUS_DEFS,
			activeReview.status ?? "RUNNING",
			activeReview.progress,
		);
		const progress = reviewProgressWords(activeReview.progress);
		return (
			<Badge variant="muted" className="max-w-full">
				<def.icon className={statusToneClass(def.badgeVariant)} aria-hidden />
				<span className="font-semibold">Review {def.label.toLowerCase()}</span>
				{progress !== undefined && (
					<>
						<ClauseDot />
						<span className="min-w-0 truncate font-normal text-muted-foreground">{progress}</span>
					</>
				)}
				<ClauseDot />
				<span className="min-w-0 truncate font-normal text-muted-foreground">
					on {activeReview.item.label}
				</span>
			</Badge>
		);
	}
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
	const progress = running ? reviewProgressWords(review.progress) : undefined;
	const runningDef =
		review.status === null
			? RUNNING
			: shownReviewStatusDef(OWN_REVIEW_STATUS_DEFS, review.status, review.progress);
	const stopped = review.status === "FAILED" || review.status === "CANCELLED";
	const Icon = running ? runningDef.icon : HistoryIcon;
	const iconTone = running ? statusToneClass(runningDef.badgeVariant) : "text-muted-foreground";

	const words = running ? (
		<>
			<span className="font-semibold">Review {runningDef.label.toLowerCase()}</span>
			{progress !== undefined && (
				<>
					<ClauseDot />
					<span className="min-w-0 truncate font-normal text-muted-foreground">{progress}</span>
				</>
			)}
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
