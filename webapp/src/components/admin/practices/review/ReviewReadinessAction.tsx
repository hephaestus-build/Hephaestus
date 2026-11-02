import { Link } from "@tanstack/react-router";

import { InlineLink } from "@/components/common/InlineLink";

import { type ReviewReadinessState, reviewReadinessTone } from "./review-readiness";

export interface ReviewReadinessActionProps {
	readiness: ReviewReadinessState;
	workspaceSlug: string;
	/** AI models is an owner's page, so anyone else is told who can act. */
	isOwner: boolean;
	/** Review settings shows this too, and already holds what turns reviews on. */
	onReviewSettings?: boolean;
}

/**
 * The way out of a readiness banner, which is the page that changes the fact it states: AI models for
 * a review model, and the Automated review tab for reviews that are off.
 */
export function ReviewReadinessAction({
	readiness,
	workspaceSlug,
	isOwner,
	onReviewSettings = false,
}: ReviewReadinessActionProps) {
	const tone = reviewReadinessTone(readiness);
	if (tone === "off" && !onReviewSettings) {
		return (
			<InlineLink
				render={
					<Link
						to="/w/$workspaceSlug/admin/practices/review"
						params={{ workspaceSlug }}
						search={{ section: "automated-review" }}
					/>
				}
			>
				Open Automated review
			</InlineLink>
		);
	}
	if (tone !== "blocked" && tone !== "unconfirmed") {
		return null;
	}
	if (!isOwner) {
		return <p>A workspace owner chooses the review model in AI models.</p>;
	}
	return (
		<InlineLink
			render={
				<Link
					to="/w/$workspaceSlug/admin/models"
					params={{ workspaceSlug }}
					search={{ purpose: "PRACTICE_REVIEW" }}
				/>
			}
		>
			Open AI models
		</InlineLink>
	);
}
