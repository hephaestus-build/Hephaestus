import { ScanSearchIcon } from "lucide-react";

import type { Practice, ReviewObservation } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { ClaimCurrentnessBadge } from "@/components/practice-vocabulary/ClaimCurrentness";
import { MARKED_INCORRECT_DEF } from "@/components/practice-vocabulary/observation-invalidation-defs";
import { observationResult } from "@/components/practice-vocabulary/observation-result";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

import { observationLevel } from "./review-levels";
import { REVIEW_PAGE_SIZE } from "./review-search";
import { ReviewArtifactLabel } from "./ReviewArtifact";
import {
	FeedbackCountsSummary,
	ObservationOriginBadge,
	ObservationResultBadge,
} from "./ReviewBadges";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewPracticeLink } from "./ReviewPracticeLink";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRow, ReviewRowList, ReviewRowMeta } from "./ReviewRow";

export type ObservationResultsState =
	| { status: "loading" }
	| { status: "empty"; filtered: false }
	| { status: "empty"; filtered: true; onClearFilters: () => void }
	| { status: "ready"; observations: ReviewObservation[] };

export interface ObservationResultsProps {
	state: ObservationResultsState;
	practices?: Practice[];
}

export function ObservationResults({ state, practices }: ObservationResultsProps) {
	if (state.status === "loading") {
		return <ReviewResultsSkeleton label="Loading observations" rows={REVIEW_PAGE_SIZE} />;
	}
	if (state.status === "empty") {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ScanSearchIcon />
					</EmptyMedia>
					<EmptyTitle>
						{state.filtered ? "No observations match these filters" : "No observations yet"}
					</EmptyTitle>
					<EmptyDescription>
						{state.filtered
							? "Every filter still applies. Clear them to see the whole list, or narrow one at a time."
							: "Observations appear after a practice review completes."}
					</EmptyDescription>
				</EmptyHeader>
				{state.filtered && (
					<EmptyContent>
						<Button variant="outline" size="sm" onClick={state.onClearFilters}>
							Clear all filters
						</Button>
					</EmptyContent>
				)}
			</Empty>
		);
	}

	return (
		<ReviewRowList label="Observations">
			{state.observations.map((observation) => (
				<ObservationRow
					key={observation.id}
					observation={observation}
					practice={practices?.find((practice) => practice.slug === observation.practiceSlug)}
				/>
			))}
		</ReviewRowList>
	);
}

export interface ObservationRowProps {
	observation: ReviewObservation;
	practice?: Practice;
}

export function ObservationRow({ observation, practice }: ObservationRowProps) {
	return (
		<ReviewRow
			status={observationResult(observation)}
			title={
				<DetailStackLink entry={observationLevel(observation.id)}>
					{observation.summary}
				</DetailStackLink>
			}
			meta={
				<>
					<ReviewRowMeta
						items={[
							<ReviewPracticeLink
								key="practice"
								practiceSlug={observation.practiceSlug}
								practiceName={observation.practiceName}
								group={observation.group}
								practice={practice}
							/>,
							<ReviewArtifactLabel key="work" reviewedWork={observation.reviewedWork} />,
							<RelativeTime key="observed" value={observation.observedAt} tooltip={false} />,
						]}
					/>
					<p>
						<FeedbackCountsSummary counts={observation.feedback} prefix="Feedback:" />
					</p>
				</>
			}
			chips={[
				{
					key: "flags",
					node: (
						<>
							<ClaimCurrentnessBadge currentness={observation.claimCurrentness} />
							{observation.invalidatedAt !== undefined && (
								<StatusBadge def={MARKED_INCORRECT_DEF} />
							)}
							<ObservationOriginBadge origin={observation.origin} />
						</>
					),
				},
				{ key: "person", width: "lg:w-36", node: <ReviewPerson person={observation.subject} /> },
				{
					key: "result",
					width: "lg:w-44",
					node: <ObservationResultBadge observation={observation} />,
				},
			]}
		/>
	);
}
