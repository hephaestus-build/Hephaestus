import { FileQuestionIcon } from "lucide-react";

import type { Practice, ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DrawerBody } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { artifactKindLabel, type KnownArtifactKind } from "@/lib/artifact-kinds";

import type { ReviewSectionState } from "./review-states";
import { ReviewArtifactLink } from "./ReviewArtifact";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewOutputSections } from "./ReviewOutputSections";

export interface ReviewedWorkLevelProps {
	workspaceSlug: string;
	nested?: boolean;
	path: LevelPath;
	artifactKind: KnownArtifactKind;
	artifactId: number;
	feedback: ReviewSectionState<ReviewFeedback>;
	observations: ReviewSectionState<ReviewObservation>;
	/** The workspace's practices, for the hover card on each observation's practice. */
	practices: Practice[] | undefined;
}

const itemsOf = <T,>(state: ReviewSectionState<T>): T[] =>
	state.status === "ready" ? state.items : [];

/**
 * Everything the reviews have said about one piece of work, across every review of it. The level
 * knows the work only by kind and id until a section answers, so the title is read off whichever
 * section answered first, and is the kind of work until one does.
 */
export function ReviewedWorkLevel({
	workspaceSlug,
	nested,
	path,
	artifactKind,
	artifactId,
	feedback,
	observations,
	practices,
}: ReviewedWorkLevelProps) {
	const feedbackItems = itemsOf(feedback);
	const observationItems = itemsOf(observations);
	const reviewedWork = feedbackItems[0]?.reviewedWork ?? observationItems[0]?.reviewedWork;
	const stillLoading = feedback.status === "loading" || observations.status === "loading";
	// Both sections have to have answered before "nothing here" is an honest thing to say: one of them
	// failing is not evidence that the other found nothing.
	const noOutput =
		feedback.status === "ready" &&
		observations.status === "ready" &&
		feedbackItems.length === 0 &&
		observationItems.length === 0;

	return (
		<>
			<ReviewLevelHeader
				nested={nested}
				path={path}
				kind="work"
				title={reviewedWork?.title ?? artifactKindLabel(artifactKind)}
				description={
					reviewedWork ? (
						<ReviewArtifactLink reviewedWork={reviewedWork} />
					) : (
						stillLoading && <Skeleton aria-hidden className="h-5 w-72 max-w-full" />
					)
				}
			/>
			<DrawerBody className="flex flex-col gap-8 pt-2">
				{noOutput ? (
					<Empty variant="outlined">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<FileQuestionIcon />
							</EmptyMedia>
							<EmptyTitle>Nothing has been reviewed on this work</EmptyTitle>
							<EmptyDescription>
								No observations or feedback are recorded against it. Either no review has run, or
								the practices in this workspace do not apply to{" "}
								{artifactKindLabel(artifactKind).toLowerCase()}s.
							</EmptyDescription>
						</EmptyHeader>
					</Empty>
				) : (
					<ReviewOutputSections
						workspaceSlug={workspaceSlug}
						scope={{ artifactKind, artifactId }}
						feedback={feedback}
						observations={observations}
						practices={practices}
					/>
				)}
			</DrawerBody>
		</>
	);
}
