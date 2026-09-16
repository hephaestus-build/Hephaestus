import { useQuery } from "@tanstack/react-query";
import { createFileRoute, notFound } from "@tanstack/react-router";

import {
	listPracticeReviewFeedbackOptions,
	listPracticeReviewObservationsOptions,
	listPracticesOptions,
} from "@/api/@tanstack/react-query.gen";
import type { PageMetadata } from "@/api/types.gen";
import { reviewArtifactTypeFromSlug } from "@/components/admin/practice-reviews/ReviewArtifact";
import {
	REVIEW_PREVIEW_SIZE,
	type ReviewSectionState,
} from "@/components/admin/practice-reviews/ReviewOutputSections";
import { ReviewTargetPage } from "@/components/admin/practice-reviews/ReviewTargetPage";
import { workspaceAdminHead } from "@/lib/page-title";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/admin/practices/reviews/targets/$artifactKind/$artifactId",
)({
	head: workspaceAdminHead("Reviewed work output"),
	loader: ({ params: { artifactKind, artifactId } }) => {
		const type = reviewArtifactTypeFromSlug(artifactKind);
		const id = Number(artifactId);
		if (!type || !Number.isSafeInteger(id) || id < 1) {
			throw notFound();
		}
		return { artifactKind: type, artifactId: id };
	},
	component: ReviewTargetRoute,
});

/**
 * Two independent reads of the same work, kept independent all the way to the page: each section
 * shows its own result, so one endpoint failing costs the reader that section and not the page.
 */
function sectionState<T>(query: {
	isLoading: boolean;
	isError: boolean;
	error: unknown;
	data?: { content?: T[]; page?: PageMetadata };
	refetch: () => unknown;
}): ReviewSectionState<T> {
	if (query.isLoading) {
		return { status: "loading" };
	}
	if (query.isError) {
		return { status: "error", error: query.error, onRetry: () => void query.refetch() };
	}
	return {
		status: "ready",
		items: query.data?.content ?? [],
		total: query.data?.page?.totalElements ?? 0,
	};
}

function ReviewTargetRoute() {
	const { workspaceSlug } = Route.useParams();
	const { artifactKind, artifactId } = Route.useLoaderData();
	const scope = { artifactKind, artifactId };

	const feedbackQuery = useQuery({
		...listPracticeReviewFeedbackOptions({
			path: { workspaceSlug },
			query: { ...scope, size: REVIEW_PREVIEW_SIZE },
		}),
	});
	const observationsQuery = useQuery({
		...listPracticeReviewObservationsOptions({
			path: { workspaceSlug },
			query: { ...scope, size: REVIEW_PREVIEW_SIZE },
		}),
	});
	// Each observation row names its practice by slug, and the hover card on that name needs the
	// practice itself. One list for the page, shared by query key with every other screen that asks
	// for it.
	const practicesQuery = useQuery({ ...listPracticesOptions({ path: { workspaceSlug } }) });

	return (
		<ReviewTargetPage
			workspaceSlug={workspaceSlug}
			artifactKind={artifactKind}
			artifactId={artifactId}
			feedback={sectionState(feedbackQuery)}
			observations={sectionState(observationsQuery)}
			practices={practicesQuery.data}
		/>
	);
}
