import { Link } from "@tanstack/react-router";
import { MessageSquareReplyIcon } from "lucide-react";
import type { ReactElement } from "react";

import type { WeeklyDigest, WeeklyDigestFeedback } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { StatusBadge } from "@/components/common/StatusBadge";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import { composeChangeSentences } from "@/components/practice-profile/compose-overview";
import { practiceLevel } from "@/components/practice-profile/practice-profile-search";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { count } from "@/components/practice-vocabulary/feedback-text";
import { FEEDBACK_WITHDRAWN_DEF } from "@/components/practice-vocabulary/feedback-withdrawal-defs";
import { FeedbackText } from "@/components/practice-vocabulary/FeedbackText";
import { PracticePill } from "@/components/practice-vocabulary/PracticePill";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from "@/components/ui/item";
import { formatDayRange } from "@/lib/dates";
import { itemName } from "@/lib/item-kinds";
import { capitalise, hasText } from "@/lib/text";

export interface WeeklyDigestCardProps {
	/** The digest as the server reads it now; absent when it could not be read. */
	digest?: WeeklyDigest;
	/** The workspace the digest's links open in. */
	workspaceSlug: string;
	/** Opens the digest's thread to write in. A read-only conversation, or the thread itself, passes none. */
	onReplyInThread?: () => void;
}

/**
 * "5–11 October 2026": Monday to Sunday of the digest's week. The server names the Monday as a
 * calendar date, which arrives as midnight UTC; it is read back as that same day here.
 */
function weekDays(weekStart: Date) {
	const year = weekStart.getUTCFullYear();
	const month = weekStart.getUTCMonth();
	const day = weekStart.getUTCDate();
	return formatDayRange(new Date(year, month, day), new Date(year, month, day + 6));
}

/** The work a piece of feedback is about, as its row names it: "Pull request #12 · Add retries". */
function workName(row: WeeklyDigestFeedback): string | undefined {
	return row.item && [itemName(row.item), row.item.title].filter(hasText).join(" · ");
}

/** Where a piece of feedback landed, as a link: the feedback on that work, the practice, or Heph's message. */
function linkTo(row: WeeklyDigestFeedback, workspaceSlug: string): ReactElement | undefined {
	switch (row.destination) {
		case "ON_WORK": {
			return (
				row.item && (
					<Link
						to="/w/$workspaceSlug/feedback/$itemId"
						params={{ workspaceSlug, itemId: row.item.id }}
					/>
				)
			);
		}
		case "PRACTICE_PAGE": {
			return (
				<Link
					to="/w/$workspaceSlug/practice-profile"
					params={{ workspaceSlug }}
					search={detailSearch(practiceLevel(row.practiceSlug))}
				/>
			);
		}
		case "CONVERSATION": {
			return row.mentorEntryId === undefined ? undefined : (
				<Link
					to="/w/$workspaceSlug/mentor"
					params={{ workspaceSlug }}
					search={
						row.mentorParentEntryId === undefined
							? { entry: row.mentorEntryId }
							: { thread: row.mentorParentEntryId, entry: row.mentorEntryId }
					}
				/>
			);
		}
	}
}

function FeedbackRow({ row, workspaceSlug }: { row: WeeklyDigestFeedback; workspaceSlug: string }) {
	if (row.withdrawn) {
		return (
			<Item variant="outline" size="sm" role="listitem">
				<ItemContent>
					<ItemTitle>
						<PracticePill name={row.practiceName} size="sm" />
						<StatusBadge def={FEEDBACK_WITHDRAWN_DEF} />
					</ItemTitle>
					<ItemDescription>A workspace admin withdrew this feedback.</ItemDescription>
				</ItemContent>
			</Item>
		);
	}
	const work = workName(row);
	// The row is the link, so the list item wraps it: a role on the link itself would hide that it opens.
	return (
		<div role="listitem">
			<Item variant="outline" size="sm" render={linkTo(row, workspaceSlug)}>
				<ItemContent>
					<ItemTitle>
						<PracticePill name={row.practiceName} size="sm" />
						<StatusBadge def={FEEDBACK_DESTINATION_DEFS[row.destination]} />
					</ItemTitle>
					{(work !== undefined || row.replaced) && (
						<ItemDescription>
							{work}
							{work !== undefined && row.replaced && ". "}
							{row.replaced && "Newer feedback replaced it."}
						</ItemDescription>
					)}
				</ItemContent>
			</Item>
		</div>
	);
}

/**
 * A weekly digest in the developer's Heph conversation: the feedback that reached them in one week,
 * each piece linked to where it landed, what changed on their Practice profile, and how many cards
 * wait unread there. Facts only, read again every time the conversation is: a Practice page card
 * leaves the waiting count once it is read, feedback that no longer shows drops out, and a
 * withdrawn card keeps only its practice. So a digest can come to show nothing, which says only
 * that, not why. It never shows a count of what reached them, a score or a streak.
 */
export function WeeklyDigestCard({
	digest,
	workspaceSlug,
	onReplyInThread,
}: WeeklyDigestCardProps) {
	const week = digest && weekDays(digest.weekStart);
	const reply = onReplyInThread && (
		<Button
			variant="outline"
			size="sm"
			className="self-start"
			aria-label={
				week === undefined
					? "Reply in thread (Weekly digest)"
					: `Reply in thread (Weekly digest, ${week})`
			}
			onClick={onReplyInThread}
		>
			<MessageSquareReplyIcon aria-hidden />
			Reply in thread
		</Button>
	);
	if (digest === undefined) {
		return (
			<Bubble variant="outline">
				<BubbleContent>
					<div className="flex flex-col gap-3">
						<p className="text-muted-foreground">We could not show this weekly digest.</p>
						{reply}
					</div>
				</BubbleContent>
			</Bubble>
		);
	}
	const changes = composeChangeSentences(digest.changes);
	const empty = digest.feedback.length === 0 && changes.length === 0 && digest.waiting === 0;
	return (
		<Bubble variant="outline">
			<BubbleContent>
				<div className="flex flex-col gap-4">
					<p className="font-medium">{week}</p>
					{empty && (
						<p className="text-muted-foreground">
							There is no feedback or profile change to show here now.
						</p>
					)}
					{digest.feedback.length > 0 && (
						<div className="flex flex-col gap-2">
							<h3 className="text-sm font-semibold">Feedback that reached you</h3>
							<ItemGroup role="list" className="gap-2">
								{digest.feedback.map((row) => (
									<FeedbackRow key={row.feedbackId} row={row} workspaceSlug={workspaceSlug} />
								))}
							</ItemGroup>
						</div>
					)}
					{changes.length > 0 && (
						<div className="flex flex-col gap-2">
							<h3 className="text-sm font-semibold">What changed</h3>
							<ul className="flex flex-col gap-1.5">
								{changes.map((segments, index) => (
									<li key={index}>
										<FeedbackText segments={segments} />
									</li>
								))}
							</ul>
						</div>
					)}
					{digest.waiting > 0 && (
						<p>
							{capitalise(count(digest.waiting, "piece of feedback", "pieces of feedback"))}{" "}
							{digest.waiting === 1 ? "is" : "are"} new on your{" "}
							<InlineLink
								render={<Link to="/w/$workspaceSlug/practice-profile" params={{ workspaceSlug }} />}
							>
								Practice profile
							</InlineLink>
							.
						</p>
					)}
					{reply && (
						<>
							<p className="text-muted-foreground">
								Reply in a thread to go through this week with Heph.
							</p>
							{reply}
						</>
					)}
				</div>
			</BubbleContent>
		</Bubble>
	);
}
