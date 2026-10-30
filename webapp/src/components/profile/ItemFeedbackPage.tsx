import { MessageSquareTextIcon } from "lucide-react";

import type { ReactElement } from "react";

import type { ItemRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { PageHeader } from "@/components/layout/PageHeader";
import { itemKindLabel, itemKindNoun, itemName } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

import { ReviewFeed } from "./ReviewFeed";
import type { FeedbackResponseControls, ReviewFeedState } from "./reviews";

export interface ItemFeedbackPageProps {
	feed: ReviewFeedState;
	/** The reader's response to each piece of feedback; absent where they may not respond. */
	responses?: FeedbackResponseControls;
	/**
	 * The way to all of the reader's feedback, for a reader who arrives from a comment on the work;
	 * absent where the workspace has no Practice profile.
	 */
	profileLink?: ReactElement;
}

const RESPONSE_GUIDE =
	"Mark feedback addressed or not applicable, or disagree and say why. Workspace admins read your reason. While your disagreement stands, a later review of this work does not raise the same point again.";

/**
 * "Your feedback on pull request #16", so a reader who arrives from a comment on the work can tell
 * it is the right one. A document or conversation has no number, so it is "this document".
 */
function feedbackTitle(item: ItemRef | undefined): string {
	if (item === undefined) {
		return "Your feedback on this work";
	}
	const noun = itemKindNoun(item.kind, 1, item.provider);
	const numbered = itemName(item) !== itemKindLabel(item.kind, 1, item.provider);
	return numbered ? `Your feedback on ${noun} ${item.label}` : `Your feedback on this ${noun}`;
}

export function ItemFeedbackPage({ feed, responses, profileLink }: ItemFeedbackPageProps) {
	// Every review here is of the one item the page is for.
	const item = feed.status === "ready" ? feed.reviews[0]?.review.item : undefined;
	const name = item && (hasText(item.title) ? item.title : item.label);
	return (
		<div className="flex max-w-4xl min-w-0 flex-col gap-6">
			<div className="space-y-2">
				<PageHeader
					icon={<MessageSquareTextIcon />}
					title={feedbackTitle(item)}
					// Read-only, the page offers no responses, so it does not describe them.
					description={responses === undefined ? undefined : RESPONSE_GUIDE}
					actions={profileLink}
				/>
				{item !== undefined && hasText(name) && (
					<p className="ps-9 text-sm font-medium break-words">
						{hasText(item.url) ? (
							<InlineLink href={item.url} external>
								{name}
							</InlineLink>
						) : (
							name
						)}
					</p>
				)}
			</div>
			<ReviewFeed
				feed={feed}
				responses={responses}
				skeletonRows={2}
				emptyTitle="No observations are available here"
				emptyDescription="There are no observations you can read here. Observations can be hidden when access to their sources cannot be verified."
			/>
		</div>
	);
}
