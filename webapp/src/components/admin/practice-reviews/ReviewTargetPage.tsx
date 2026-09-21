import { FileQuestionIcon } from "lucide-react";

import type { Practice, ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { artifactKindLabel, type KnownArtifactKind } from "@/lib/artifact-kinds";

import { ReviewArtifactLink } from "./ReviewArtifact";
import { ReviewBreadcrumbs } from "./ReviewBreadcrumbs";
import { ReviewDetailHeader } from "./ReviewDetailHeader";
import { ReviewOutputSections, type ReviewSectionState } from "./ReviewOutputSections";

export interface ReviewTargetPageProps {
	workspaceSlug: string;
	artifactKind: KnownArtifactKind;
	artifactId: number;
	feedback: ReviewSectionState<ReviewFeedback>;
	observations: ReviewSectionState<ReviewObservation>;
	/**
	 * The workspace's practices, which each observation row's practice link shows as a hover card.
	 * Optional: the card is the only thing that needs them, and nothing it holds is load-bearing.
	 */
	practices?: Practice[];
}

const itemsOf = <T,>(state: ReviewSectionState<T>): T[] =>
	state.status === "ready" ? state.items : [];

/**
 * No eyebrow above the heading: the link's own mark and words say what kind of work this is, which
 * is a fact about *this* work, while a label for the page restates the breadcrumb one line above it.
 *
 * <p>The work is not fetched by name — nothing on this route knows its title until a review of it
 * comes back — so the heading is read off whichever section answered first, and is a skeleton until
 * one of them does.
 */
export function ReviewTargetPage({
	workspaceSlug,
	artifactKind,
	artifactId,
	feedback,
	observations,
	practices,
}: ReviewTargetPageProps) {
	const scope = { artifactKind, artifactId };
	const feedbackItems = itemsOf(feedback);
	const observationItems = itemsOf(observations);
	const artifact = feedbackItems[0]?.artifact ?? observationItems[0]?.artifact;
	const stillLoading = feedback.status === "loading" || observations.status === "loading";
	// Both sections have to have answered before "nothing here" is an honest thing to say: one of them
	// failing is not evidence that the other found nothing.
	const noOutput =
		feedback.status === "ready" &&
		observations.status === "ready" &&
		feedbackItems.length === 0 &&
		observationItems.length === 0;

	return (
		<article className="max-w-4xl min-w-0 space-y-8">
			<ReviewBreadcrumbs workspaceSlug={workspaceSlug} />
			{noOutput ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<FileQuestionIcon />
						</EmptyMedia>
						<EmptyTitle>Nothing has been reviewed on this work</EmptyTitle>
						<EmptyDescription>
							No observations or feedback are recorded against it. Either no review has run, or the
							practices in this workspace do not apply to{" "}
							{artifactKindLabel(artifactKind).toLowerCase()}s.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<>
					<ReviewDetailHeader
						title={
							!artifact && stillLoading ? (
								// A heading whose only content is a skeleton is an empty heading to a screen
								// reader, which is a landmark that announces nothing at all.
								<>
									<span className="sr-only">Loading the reviewed work</span>
									<Skeleton className="h-8 w-72 max-w-full" />
								</>
							) : (
								(artifact?.title ?? artifactKindLabel(artifactKind))
							)
						}
						provenance={artifact && <ReviewArtifactLink artifact={artifact} className="text-sm" />}
					/>

					<ReviewOutputSections
						workspaceSlug={workspaceSlug}
						scope={scope}
						feedback={feedback}
						observations={observations}
						practices={practices}
					/>
				</>
			)}
		</article>
	);
}
