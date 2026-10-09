import { MessageSquareTextIcon, ScanSearchIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { AgentJob, Practice, ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Section } from "@/components/layout/Section";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import type { KnownArtifactKind } from "@/lib/artifact-kinds";

import { FeedbackRow } from "./FeedbackResults";
import { ObservationRow } from "./ObservationResults";
import type { ReviewListTarget } from "./review-outcomes";
import { REVIEW_PREVIEW_SIZE } from "./review-search";
import type { ReviewSectionState } from "./review-states";
import { ReviewListLink } from "./ReviewListLink";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";

/**
 * Not "nothing was found": a review that ran no model never got as far as looking, so reading its
 * empty result as a clean bill of health would be backwards.
 */
const NO_ASSESSMENT: Partial<
	Record<AgentJob["reviewOutcome"], { title: string; description: string }>
> = {
	INSUFFICIENT_EVIDENCE: {
		title: "Nothing was assessed",
		description:
			"The review stopped before it assessed anything. The material it needed was missing, unreadable, out of date, or not something it was allowed to read. No practice was judged. This is not a review that looked and found nothing.",
	},
	COALESCED: {
		title: "Answered by an earlier review",
		description:
			"An earlier review had already checked every ready practice on the same code, so this review did not assess them again. Open the earlier review to see its observations.",
	},
	SUPERSEDED: {
		title: "Replaced by a newer review",
		description:
			"A newer admitted review carries this review’s practices on the updated work and may still be waiting or running.",
	},
};

export interface ReviewOutputScope {
	agentJobId?: string;
	artifactKind?: KnownArtifactKind;
	artifactId?: number;
}

export interface ReviewOutputSectionsProps {
	workspaceSlug: string;
	scope: ReviewOutputScope;
	feedback: ReviewSectionState<ReviewFeedback>;
	observations: ReviewSectionState<ReviewObservation>;
	/** The workspace's practices, for the hover card on each observation's practice. */
	practices: Practice[] | undefined;
	/**
	 * Tells "looked and found nothing" from "declined to look". Absent on views that span several
	 * reviews, which have no single outcome.
	 */
	outcome?: AgentJob["reviewOutcome"];
}

/** What one review, or one piece of work, produced: its observations, then its feedback. */
export function ReviewOutputSections({
	workspaceSlug,
	scope,
	feedback,
	observations,
	practices,
	outcome,
}: ReviewOutputSectionsProps) {
	return (
		<>
			<ObservationsSection
				workspaceSlug={workspaceSlug}
				state={observations}
				list={{ list: "observations", search: scope }}
				practices={practices}
				outcome={outcome}
			/>
			<FeedbackSection
				workspaceSlug={workspaceSlug}
				state={feedback}
				list={{ list: "feedback", search: scope }}
				outcome={outcome}
			/>
		</>
	);
}

interface SectionProps<T> {
	workspaceSlug: string;
	state: ReviewSectionState<T>;
	/** The whole list the section previews. */
	list: ReviewListTarget;
	outcome?: AgentJob["reviewOutcome"];
}

/** The rows a full list shows, first few only, so a record looks the same wherever it is met. */
export function ObservationsSection({
	practices,
	...props
}: SectionProps<ReviewObservation> & { practices: Practice[] | undefined }) {
	return (
		<PreviewSection
			{...props}
			title="Observations"
			icon={<ScanSearchIcon />}
			empty="No observations were recorded"
		>
			{(items) =>
				items.map((observation) => (
					<ObservationRow
						key={observation.id}
						observation={observation}
						practice={practices?.find((practice) => practice.slug === observation.practiceSlug)}
					/>
				))
			}
		</PreviewSection>
	);
}

function FeedbackSection(props: SectionProps<ReviewFeedback>) {
	return (
		<PreviewSection
			{...props}
			title="Feedback"
			icon={<MessageSquareTextIcon />}
			empty="No feedback was composed"
		>
			{(items) => items.map((item) => <FeedbackRow key={item.id} feedback={item} />)}
		</PreviewSection>
	);
}

function PreviewSection<T>({
	workspaceSlug,
	state,
	list,
	outcome,
	title,
	icon,
	empty,
	children,
}: SectionProps<T> & {
	title: "Feedback" | "Observations";
	icon: ReactNode;
	empty: string;
	children: (items: T[]) => ReactNode;
}) {
	const noun = title.toLowerCase();
	const unassessed = outcome === undefined ? undefined : NO_ASSESSMENT[outcome];
	return (
		<Section
			level={3}
			title={title}
			// Whenever there is anything to list, even when all of it is shown here: the list is where
			// it is filtered, paged and compared, which a preview cannot do.
			actions={
				state.status === "ready" && state.total > 0 ? (
					<InlineLink
						tone="count"
						className="text-sm"
						render={<ReviewListLink workspaceSlug={workspaceSlug} destination={list} />}
					>
						See all {state.total} {noun}
					</InlineLink>
				) : undefined
			}
		>
			{state.status === "loading" && (
				<ReviewResultsSkeleton label={`Loading ${noun}`} rows={REVIEW_PREVIEW_SIZE} />
			)}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title={`We could not load ${noun}`}
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "pending" && (
				<p className="text-sm text-muted-foreground">
					The review has not finished processing its results.
				</p>
			)}
			{state.status === "ready" &&
				(state.items.length === 0 ? (
					<Empty variant="outlined">
						<EmptyHeader>
							<EmptyMedia variant="icon">{icon}</EmptyMedia>
							<EmptyTitle>{unassessed?.title ?? empty}</EmptyTitle>
							{unassessed !== undefined && (
								<EmptyDescription>{unassessed.description}</EmptyDescription>
							)}
						</EmptyHeader>
					</Empty>
				) : (
					<ReviewRowList label={title}>{children(state.items)}</ReviewRowList>
				))}
		</Section>
	);
}
