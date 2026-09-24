import { Link } from "@tanstack/react-router";
import { ScanSearchIcon } from "lucide-react";

import type { Practice, ReviewObservation } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { ClaimCurrentnessBadge } from "@/components/practice-vocabulary/ClaimCurrentness";
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

import { REVIEW_PAGE_SIZE, type ReviewScopeSearch } from "./review-search";
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
	workspaceSlug: string;
	state: ObservationResultsState;
	practices?: Practice[];
}

export function ObservationResults({ workspaceSlug, state, practices }: ObservationResultsProps) {
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
					workspaceSlug={workspaceSlug}
					observation={observation}
					practice={practices?.find((practice) => practice.slug === observation.practiceSlug)}
				/>
			))}
		</ReviewRowList>
	);
}

export interface ObservationRowProps {
	workspaceSlug: string;
	observation: ReviewObservation;
	practice?: Practice;
	scope?: ReviewScopeSearch;
}

export function ObservationRow({
	workspaceSlug,
	observation,
	practice,
	scope,
}: ObservationRowProps) {
	return (
		<ReviewRow
			status={observationResult(observation)}
			title={
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews/observations/$observationId"
					params={{ workspaceSlug, observationId: observation.id }}
					search={scope ?? ((previous) => previous)}
				>
					{observation.summary}
				</Link>
			}
			meta={
				<>
					<ReviewRowMeta
						items={[
							<ReviewPracticeLink
								key="practice"
								workspaceSlug={workspaceSlug}
								practiceSlug={observation.practiceSlug}
								practiceName={observation.practiceName}
								group={observation.group}
								practice={practice}
							/>,
							<ReviewArtifactLabel key="work" artifact={observation.artifact} />,
							<RelativeTime key="observed" value={observation.observedAt} tooltip={false} />,
						]}
					/>
					<p>
						<FeedbackCountsSummary counts={observation.feedbackDisposition} prefix="Feedback:" />
					</p>
				</>
			}
			chips={[
				{
					key: "flags",
					node: (
						<>
							<ClaimCurrentnessBadge currentness={observation.claimCurrentness} />
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
