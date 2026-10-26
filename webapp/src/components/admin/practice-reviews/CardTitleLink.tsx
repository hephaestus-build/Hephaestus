import { InlineLink } from "@/components/common/InlineLink";

import type { ReviewListTarget } from "./review-outcomes";
import { ReviewListLink } from "./ReviewListLink";

/**
 * A card's title as the link to its tab, in the card's scope: the title is where one clicks to see
 * the rows a card sums up, and the tabs sit far above it. It follows the link convention
 * (`webapp/DESIGN.md` § Practice surfaces palette): plain title text at rest, the accent with an
 * underline on hover and focus, like every other link on the page. Without a workspace, as in a
 * preview, it stays a plain title.
 */
export function CardTitleLink({
	workspaceSlug,
	destination,
	children,
}: {
	workspaceSlug?: string;
	destination: ReviewListTarget;
	/** The title's words. */
	children: string;
}) {
	if (workspaceSlug === undefined) {
		return children;
	}
	return (
		<InlineLink render={<ReviewListLink workspaceSlug={workspaceSlug} destination={destination} />}>
			{children}
		</InlineLink>
	);
}
