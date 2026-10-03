import { useInfiniteQuery } from "@tanstack/react-query";
import { createFileRoute, notFound } from "@tanstack/react-router";

import { listReviewedWorkReviewRunsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { reviewRunFeedState } from "@/components/profile/review-runs";
import { ReviewedWorkFeedbackPage } from "@/components/profile/ReviewedWorkFeedbackPage";
import { useFeedbackResponseWrite } from "@/hooks/use-feedback-response-write";
import { REVIEW_RUN_PAGE_SIZE } from "@/hooks/use-practice-group-detail";
import { pageHead } from "@/lib/page-title";
import { useAuth } from "@/runtime/auth/AuthContext";
import { slicePageParams } from "@/runtime/tanstack-query/spring-page";

/**
 * The address every comment Hephaestus posts on reviewed work ends with, so it is a contract with
 * comments already out there: `artifactKind` is the wire id (`scm.pull_request`), as on the review
 * trace, and the page shows only the reader's own reviews of the work.
 */
export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/feedback/$artifactKind/$artifactId",
)({
	remountDeps: ({ params }) => params,
	loader: ({ params: { artifactId } }) => {
		const id = Number(artifactId);
		if (!Number.isSafeInteger(id) || id < 1) {
			throw notFound();
		}
		return { artifactId: id };
	},
	head: pageHead("Your feedback on this work"),
	component: ReviewedWorkFeedbackRoute,
});

function ReviewedWorkFeedbackRoute() {
	const readOnly = useAuth().userView !== undefined;
	const { workspaceSlug, artifactKind } = Route.useParams();
	const { artifactId } = Route.useLoaderData();
	const runs = useInfiniteQuery({
		...listReviewedWorkReviewRunsInfiniteOptions({
			path: { workspaceSlug, artifactKind, artifactId },
			query: { size: REVIEW_RUN_PAGE_SIZE },
		}),
		...slicePageParams,
	});
	const { respond, pendingResponses } = useFeedbackResponseWrite(workspaceSlug, () => undefined);
	return (
		<ReviewedWorkFeedbackPage
			feed={reviewRunFeedState(runs)}
			observations={readOnly ? undefined : { onRespond: respond, pendingResponses }}
		/>
	);
}
