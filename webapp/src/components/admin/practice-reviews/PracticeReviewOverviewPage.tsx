import type { ReactNode } from "react";

import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "@/components/activity/activity-range";
import { RangeControls } from "@/components/activity/RangeControls";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Section } from "@/components/layout/Section";

import { PracticeCountsTable } from "./PracticeCountsTable";
import type { OutcomeScope } from "./review-outcomes";
import type { PracticeReviewOverviewState } from "./review-states";
import { ReviewAttention, type ReviewAttentionProps } from "./ReviewAttention";
import { ReviewPipeline } from "./ReviewPipeline";

export interface PracticeReviewOverviewPageProps {
	workspaceSlug: string;
	range: ActivityRange;
	onRangeChange: (range: ActivityRange) => void;
	/** The range as the lists' day filters, so every count opens the rows it counts. */
	scope: OutcomeScope;
	overview: PracticeReviewOverviewState;
	attention: Omit<ReviewAttentionProps, "workspaceSlug" | "rangeInSentence">;
	/** Leads the page: whatever stops reviews from running. */
	banner?: ReactNode;
}

/**
 * The home of Practice reviews, in the order an admin's questions come: can reviews run, what do I
 * owe them, what did they do, and which practices did it.
 */
export function PracticeReviewOverviewPage({
	workspaceSlug,
	range,
	onRangeChange,
	scope,
	overview,
	attention,
	banner,
}: PracticeReviewOverviewPageProps) {
	const rangeDef = ACTIVITY_RANGE_DEFS[range];
	return (
		<div className="space-y-10">
			{banner}
			<ReviewAttention
				workspaceSlug={workspaceSlug}
				rangeInSentence={rangeDef.inSentence}
				{...attention}
			/>
			<Section
				title="What the reviews did"
				size="lg"
				actions={
					<RangeControls
						range={range}
						onRangeChange={onRangeChange}
						updating={overview.status === "ready" && overview.stale}
					/>
				}
			>
				{overview.status === "error" ? (
					<QueryErrorAlert
						error={overview.error}
						title="Couldn't load what the reviews did"
						onRetry={overview.onRetry}
					/>
				) : (
					<ReviewPipeline workspaceSlug={workspaceSlug} state={overview} scope={scope} />
				)}
			</Section>
			{overview.status !== "error" && (
				<Section
					title="Practices"
					size="lg"
					description={`How the reviews judged each practice in ${rangeDef.inSentence}, busiest first.`}
				>
					<PracticeCountsTable workspaceSlug={workspaceSlug} state={overview} />
				</Section>
			)}
		</div>
	);
}
