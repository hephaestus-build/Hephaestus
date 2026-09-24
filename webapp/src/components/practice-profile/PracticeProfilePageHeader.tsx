import { ArrowRightIcon, GitCommitHorizontalIcon } from "lucide-react";

import { cn } from "cn";
import type { ReviewRunRef } from "@/api/types.gen";
import { count } from "@/components/common/feedback-text";
import { InlineLink } from "@/components/common/InlineLink";
import { PrimaryButton } from "@/components/common/PrimaryButton";
import {
	PracticeGroupStandingRing,
	type StandingCounts,
} from "@/components/practice-vocabulary/PracticeGroupStandingRing";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { asDate, formatDayTime } from "@/lib/dates";

export interface PracticeProfilePageHeaderProps {
	/**
	 * The chip above the title: when the latest review ran and on what; omitted while no review has
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
	/** The "See all practices" button; without a handler it renders disabled. */
	onSeeAllPractices?: () => void;
	isLoading?: boolean;
	className?: string;
}

/** A document title longer than this is cut in the chip, with the full title on its `title`. */
const WORK_LABEL_MAX = 28;

export function PracticeProfilePageHeader({
	latestRun,
	counts,
	practiceCount,
	groupCount,
	onSeeAllPractices,
	isLoading = false,
	className,
}: PracticeProfilePageHeaderProps) {
	return (
		<header
			className={cn("flex flex-wrap items-center justify-between gap-x-4 gap-y-3", className)}
		>
			<div className="flex min-w-0 flex-col gap-1">
				{isLoading ? (
					<>
						<Skeleton className="mb-1.5 h-5 w-64 rounded-full" />
						<Skeleton className="h-8 w-48" />
						<Skeleton className="h-5 w-80 max-w-full" />
					</>
				) : (
					<>
						{latestRun && <LatestRunChip run={latestRun} />}
						<h1 className="text-2xl font-semibold tracking-tight">Practice profile</h1>
						<p className="text-sm text-muted-foreground">
							Practice feedback on your work, visible only to you.
						</p>
					</>
				)}
			</div>
			<StandingSummaryBox
				ring={<PracticeGroupStandingRing counts={counts} />}
				label={
					practiceCount === 0 ? "No practices set up yet" : countLabel(practiceCount, groupCount)
				}
				counts={counts}
				action={
					<PrimaryButton
						disabled={isLoading || onSeeAllPractices === undefined}
						onClick={onSeeAllPractices}
					>
						See all practices
						<ArrowRightIcon aria-hidden />
					</PrimaryButton>
				}
				isLoading={isLoading}
			/>
		</header>
	);
}

function LatestRunChip({ run }: { run: ReviewRunRef }) {
	const at = asDate(run.at);
	const { label, url } = run.reviewedWork;
	const cut = label.length > WORK_LABEL_MAX;
	const shownLabel = cut ? `${label.slice(0, WORK_LABEL_MAX).trimEnd()}…` : label;
	return (
		<div className="mb-1.5 flex max-w-full">
			<Badge variant="outline" className="max-w-full">
				<GitCommitHorizontalIcon className="text-muted-foreground" aria-hidden />
				<span className="font-semibold">Latest run</span>
				{at && (
					<time dateTime={at.toISOString()} className="font-normal text-muted-foreground">
						{formatDayTime(at)}
					</time>
				)}
				<span className="text-border" aria-hidden>
					·
				</span>
				<span className="min-w-0 truncate font-normal text-muted-foreground">
					after{" "}
					<InlineLink href={url} external title={cut ? label : undefined}>
						{shownLabel}
					</InlineLink>
				</span>
			</Badge>
		</div>
	);
}

/** "16 practices in 5 groups": one clause, so both counts are digits as soon as either is. */
function countLabel(practiceCount: number, groupCount: number): string {
	const digits = Math.max(practiceCount, groupCount) >= 10;
	return `${count(practiceCount, "practice", "practices", digits)} in ${count(groupCount, "group", "groups", digits)}`;
}
