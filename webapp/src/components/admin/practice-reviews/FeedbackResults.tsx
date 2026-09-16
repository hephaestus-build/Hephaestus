import { Link } from "@tanstack/react-router";
import { MessageSquareTextIcon } from "lucide-react";

import type { ReviewFeedback } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import { deliveryOutcome } from "@/components/practice-vocabulary/delivery-outcome-defs";
import { DELIVERY_PLACE_DEFS } from "@/components/practice-vocabulary/delivery-place-defs";
import { withholdingReasonSentence } from "@/components/practice-vocabulary/withholding-defs";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { hasText } from "@/lib/text";

import { feedbackPreviewText } from "./feedback-preview";
import { REVIEW_PAGE_SIZE, type ReviewScopeSearch } from "./review-search";
import { ReviewArtifactLabel } from "./ReviewArtifact";
import { ReviewPerson } from "./ReviewPerson";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRow, ReviewRowList, ReviewRowMeta } from "./ReviewRow";

/** See `ObservationResultsState`: a filtered empty state has to carry the way out of itself. */
export type FeedbackResultsState =
	| { status: "loading" }
	| { status: "empty"; filtered: false }
	| { status: "empty"; filtered: true; onClearFilters: () => void }
	| { status: "ready"; feedback: ReviewFeedback[] };

export interface FeedbackResultsProps {
	workspaceSlug: string;
	state: FeedbackResultsState;
}

export function FeedbackResults({ workspaceSlug, state }: FeedbackResultsProps) {
	if (state.status === "loading") {
		return <ReviewResultsSkeleton label="Loading feedback" rows={REVIEW_PAGE_SIZE} />;
	}
	if (state.status === "empty") {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<MessageSquareTextIcon />
					</EmptyMedia>
					<EmptyTitle>
						{state.filtered ? "No feedback matches these filters" : "No feedback yet"}
					</EmptyTitle>
					<EmptyDescription>
						{state.filtered
							? "Every filter still applies. Clear them to see the whole list, or narrow one at a time."
							: "Delivered and withheld feedback appears here after reviews run."}
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
		<ReviewRowList label="Feedback, newest first">
			{state.feedback.map((item) => (
				<FeedbackRow key={item.id} workspaceSlug={workspaceSlug} feedback={item} />
			))}
		</ReviewRowList>
	);
}

export interface FeedbackRowProps {
	workspaceSlug: string;
	feedback: ReviewFeedback;
	/** See `ObservationRow`: the list carries its filters forward, a scoped section carries its scope. */
	scope?: ReviewScopeSearch;
}

/**
 * The row's name is the feedback's own opening words, because that is the only text that tells two
 * rows apart — a title built from the recipient repeats down the page. The person goes in a chip,
 * where it is scanned rather than read.
 *
 * <p>Place and outcome stay separate: the badge says what happened, the meta line says where it was
 * going. A withheld row also carries its own precise reason, because the outcome badge only says
 * that something stopped it.
 */
export function FeedbackRow({ workspaceSlug, feedback, scope }: FeedbackRowProps) {
	const place = DELIVERY_PLACE_DEFS[feedback.channel];
	return (
		<ReviewRow
			status={deliveryOutcome(feedback)}
			title={
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews/delivery/$feedbackId"
					params={{ workspaceSlug, feedbackId: feedback.id }}
					search={scope ?? ((previous) => previous)}
					className="line-clamp-2"
				>
					{/* Feedback whose preview is nothing but a code quote has a body and no prose to show
					    for it, which is not the same state as feedback nobody has composed yet. */}
					{feedbackPreviewText(feedback) ??
						(hasText(feedback.bodyPreview)
							? "Opens with a quote from the work…"
							: "No feedback text was composed")}
				</Link>
			}
			meta={
				<>
					<ReviewRowMeta
						items={[
							place.label,
							<ReviewArtifactLabel key="work" artifact={feedback.artifact} />,
							`${feedback.observationCount} ${feedback.observationCount === 1 ? "observation" : "observations"}`,
							// See `ObservationRow`: no hover target under a stretched row link.
							<RelativeTime key="composed" value={feedback.createdAt} tooltip={false} />,
						]}
					/>
					{feedback.suppressionReason && (
						<p>{withholdingReasonSentence(feedback.suppressionReason)}</p>
					)}
				</>
			}
			chips={[
				// Both facts are on every row, which is what earns them reserved slots: the outcome badge
				// then sits at one x down the list however long the recipient's name is. A badge that only
				// some rows carry belongs in a free chip instead — see `ReviewRowChip`.
				{ key: "person", width: "lg:w-40", node: <ReviewPerson person={feedback.recipient} /> },
				{ key: "outcome", width: "lg:w-48", node: <StatusBadge def={deliveryOutcome(feedback)} /> },
			]}
		/>
	);
}
