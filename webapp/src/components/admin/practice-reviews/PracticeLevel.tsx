import { Link } from "@tanstack/react-router";
import { PencilIcon, ScanSearchIcon, SlidersHorizontalIcon } from "lucide-react";

import { cn } from "cn";
import type { Practice, PracticeReviewCounts, ReviewObservation } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import {
	practiceFormLevel,
	practiceSetupLevel,
} from "@/components/admin/practices/practice-search";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { AutonomyBadge } from "@/components/practice-vocabulary/AutonomyBadge";
import { MARKED_INCORRECT_DEF } from "@/components/practice-vocabulary/observation-invalidation-defs";
import { buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

import { OutcomeMix } from "./OutcomeMix";
import {
	feedbackSlots,
	markedIncorrectSlot,
	OUTCOME_LEGEND_LABELS,
	observationSlots,
	type OutcomeScope,
	slotsTotal,
} from "./review-outcomes";
import type { ReviewSectionState } from "./review-states";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ObservationsSection } from "./ReviewOutputSections";

export interface PracticeLevelProps {
	workspaceSlug: string;
	nested?: boolean;
	path: LevelPath;
	practiceSlug: string;
	/** The practice as the workspace defines it, for its name, why it matters and its autonomy. */
	practice: Practice | undefined;
	/** "Last 30 days": the range every count on the level covers. */
	rangeLabel: string;
	/** The range as the lists' day filters. */
	scope: OutcomeScope;
	/** The practice's counts in the range; absent when it recorded nothing there. */
	counts: PanelState<{ counts?: PracticeReviewCounts; stale: boolean }>;
	/** Its most recent observations in the range. */
	observations: ReviewSectionState<ReviewObservation>;
}

/**
 * One practice as the reviews saw it: how much autonomy it runs with, how its observations turned
 * out, what became of the feedback citing it, and what it observed most recently. The footer edits
 * its definition, which is where a practice that reviews wrongly is fixed.
 */
export function PracticeLevel({
	workspaceSlug,
	nested,
	path,
	practiceSlug,
	practice,
	rangeLabel,
	scope,
	counts,
	observations,
}: PracticeLevelProps) {
	const practiceScope = { ...scope, practiceSlug };
	const name =
		practice?.name ?? (counts.status === "ready" ? counts.counts?.practiceName : undefined);
	return (
		<>
			<ReviewLevelHeader
				nested={nested}
				path={path}
				kind="practice"
				loading={name === undefined && counts.status === "loading"}
				title={name ?? practiceSlug}
				chips={practice && <AutonomyBadge autonomy={practice.autonomy.effective} />}
				description={<p>{rangeLabel}</p>}
			/>
			<DrawerBody className="flex flex-col gap-8 pt-2">
				{hasText(practice?.whyItMatters) && (
					<p className="max-w-prose text-sm leading-relaxed text-muted-foreground">
						{practice.whyItMatters}
					</p>
				)}
				{practice && (
					<p className="text-sm text-muted-foreground">
						<InlineLink
							className="font-medium"
							render={
								<Link to="/w/$workspaceSlug/admin/practices/review" params={{ workspaceSlug }} />
							}
						>
							Change its autonomy in Review settings
						</InlineLink>
					</p>
				)}
				<Counts workspaceSlug={workspaceSlug} counts={counts} scope={practiceScope} />
				<ObservationsSection
					workspaceSlug={workspaceSlug}
					state={observations}
					list={{
						list: "observations",
						search: { from: scope.from, to: scope.to, practiceSlug: [practiceSlug] },
					}}
					practices={practice && [practice]}
				/>
			</DrawerBody>
			<DrawerFooter>
				<Link
					to="/w/$workspaceSlug/admin/practices"
					params={{ workspaceSlug }}
					search={detailSearch(practiceSetupLevel(practiceSlug))}
					className={cn(buttonVariants({ variant: "outline" }), "w-full sm:w-auto")}
				>
					<SlidersHorizontalIcon />
					Open in Practice setup
				</Link>
				<Link
					to="/w/$workspaceSlug/admin/practices"
					params={{ workspaceSlug }}
					search={detailSearch(practiceFormLevel(practiceSlug))}
					className={cn(buttonVariants(), "w-full sm:w-auto")}
				>
					<PencilIcon />
					Edit practice
				</Link>
			</DrawerFooter>
		</>
	);
}

function Counts({
	workspaceSlug,
	counts,
	scope,
}: {
	workspaceSlug: string;
	counts: PracticeLevelProps["counts"];
	scope: OutcomeScope;
}) {
	if (counts.status === "error") {
		return (
			<QueryErrorAlert
				error={counts.error}
				title="Couldn't load this practice's counts"
				onRetry={counts.onRetry}
			/>
		);
	}
	// Another range's "not checked", standing in while this one loads, says nothing about this one.
	if (counts.status === "loading" || (counts.stale && counts.counts === undefined)) {
		return (
			<div className="grid gap-6 sm:grid-cols-2" aria-hidden>
				{Array.from({ length: 2 }, (_, index) => (
					<div key={index} className="space-y-2">
						<Skeleton className="h-7 w-32" />
						<Skeleton className="h-4 w-48" />
					</div>
				))}
			</div>
		);
	}
	if (counts.counts === undefined) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ScanSearchIcon />
					</EmptyMedia>
					<EmptyTitle>Not checked in this range</EmptyTitle>
					<EmptyDescription>
						No review recorded an observation about this practice. It may not apply to the work that
						was reviewed, or it may be turned off.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	const { observations, observationsInvalidated, feedback } = counts.counts;
	const outcomes = observationSlots(observations, scope);
	const total = slotsTotal(outcomes);
	const incorrect = markedIncorrectSlot(observationsInvalidated, scope);
	const feedbackFamilies = feedbackSlots(feedback, scope);
	return (
		<div
			aria-busy={counts.stale || undefined}
			className={cn("grid gap-6 sm:grid-cols-2", counts.stale && STALE)}
		>
			<div className="space-y-2">
				<OutcomeMix
					workspaceSlug={workspaceSlug}
					total={total}
					slots={outcomes}
					flags={{
						label: OUTCOME_LEGEND_LABELS.checkedObservations,
						// "1 of 33 marked incorrect": the share an admin found wrong, out of all of them.
						slots: [{ ...incorrect, label: `of ${total} ${incorrect.label}` }],
					}}
					noun={(count) => (count === 1 ? "observation" : "observations")}
					label={OUTCOME_LEGEND_LABELS.observations}
				/>
				{observationsInvalidated > 0 && (
					<p className="text-xs text-muted-foreground">{MARKED_INCORRECT_DEF.note}</p>
				)}
			</div>
			<OutcomeMix
				workspaceSlug={workspaceSlug}
				total={slotsTotal(feedbackFamilies)}
				slots={feedbackFamilies}
				noun={(count) =>
					count === 1 ? "piece of feedback cited it" : "pieces of feedback cited it"
				}
				label={OUTCOME_LEGEND_LABELS.feedback}
			/>
		</div>
	);
}
