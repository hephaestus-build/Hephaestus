import type { ReactNode } from "react";

import type { Practice } from "@/api/types.gen";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "@/components/activity/activity-range";
import { RangeControls } from "@/components/activity/RangeControls";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Section } from "@/components/layout/Section";

import { PracticeCountsTable } from "./PracticeCountsTable";
import type { OutcomeScope } from "./review-outcomes";
import type { PracticeReviewOverviewState, PreviousReviewPeriodState } from "./review-states";
import { ReviewAttention, type ReviewAttentionProps } from "./ReviewAttention";
import { ReviewPipeline } from "./ReviewPipeline";

export interface PracticeReviewOverviewPageProps {
	workspaceSlug: string;
	range: ActivityRange;
	onRangeChange: (range: ActivityRange) => void;
	/** The range as the lists' day filters, so every count opens the rows it counts. */
	scope: OutcomeScope;
	overview: PracticeReviewOverviewState;
	/** The period before the range, which the stages' totals are set against. */
	previous: PreviousReviewPeriodState;
	attention: Omit<ReviewAttentionProps, "workspaceSlug" | "rangeInSentence">;
	/** The workspace's practices, for how many recorded nothing; absent while they load. */
	practices: Practice[] | undefined;
	/** Leads the page: whatever stops reviews from running. */
	banner?: ReactNode;
}

/**
 * The home of Practice reviews, in the order an admin's questions come: can reviews run, what do I
 * owe them, what did they do, and which practices did it. The range heads the page because it
 * governs everything below it except the approvals, which are owed whenever they were composed.
 */
export function PracticeReviewOverviewPage({
	workspaceSlug,
	range,
	onRangeChange,
	scope,
	overview,
	previous,
	attention,
	practices,
	banner,
}: PracticeReviewOverviewPageProps) {
	const rangeDef = ACTIVITY_RANGE_DEFS[range];
	return (
		<div className="space-y-10">
			<div className="space-y-4">
				<div className="flex justify-end">
					<RangeControls
						range={range}
						onRangeChange={onRangeChange}
						updating={overview.status === "ready" && overview.stale}
					/>
				</div>
				{banner}
			</div>
			<ReviewAttention
				workspaceSlug={workspaceSlug}
				rangeInSentence={rangeDef.inSentence}
				{...attention}
			/>
			<Section title="What the reviews did" size="lg" description={rangeDef.label}>
				{overview.status === "error" ? (
					<QueryErrorAlert
						error={overview.error}
						title="Couldn't load what the reviews did"
						onRetry={overview.onRetry}
					/>
				) : (
					<ReviewPipeline
						workspaceSlug={workspaceSlug}
						state={overview}
						previous={previous}
						scope={scope}
					/>
				)}
			</Section>
			{overview.status !== "error" && (
				<Section
					title="Practices"
					size="lg"
					description={`How the reviews judged each practice in ${rangeDef.inSentence}, busiest first.`}
				>
					<PracticeCountsTable
						workspaceSlug={workspaceSlug}
						state={overview}
						practices={practices}
					/>
				</Section>
			)}
		</div>
	);
}
