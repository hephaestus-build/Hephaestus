import { FileCodeIcon, MessageSquareTextIcon } from "lucide-react";

import { cn } from "cn";
import { formatDateTime, formatRelative } from "~/components/common/format";
import { where } from "~/components/report/comment-location";
import { PageLink } from "~/components/report/PageLink";
import { INDENT, ListSkeleton, LoadError } from "~/components/report/report-parts";
import type { Loadable } from "~/components/report/report-summary";
import type { WorkComment, WorkFeedback } from "~/shared/review-context";
import type { WorkPageView } from "~/shared/work-url";

export interface CommentListProps {
	feedback: Loadable<WorkFeedback>;
	/** Which view of the work the page shows: on the changes, comments on lines come first. */
	view: WorkPageView;
	/** The provider page's origin, the only one a comment link may lead to. */
	pageOrigin: string;
	/** When the answer was read, which relative times are measured from. */
	now: string;
	onRetry: () => void;
}

function byView(view: WorkPageView) {
	const rank = (comment: WorkComment) =>
		(comment.kind === "INLINE") === (view === "changes") ? 0 : 1;
	return (a: WorkComment, b: WorkComment): number => rank(a) - rank(b);
}

/**
 * One comment Hephaestus posted for the reader. Where it is is the link to it — the provider shows the
 * comment, its replies and its resolve control there — and what it is about and when it came are
 * the row's quiet second line. A comment whose address was not recorded says so instead of linking.
 */
function Comment({
	comment,
	pageOrigin,
	now,
}: {
	comment: WorkComment;
	pageOrigin: string;
	now: string;
}) {
	const Icon = comment.kind === "INLINE" ? FileCodeIcon : MessageSquareTextIcon;
	const label = where(comment, false);
	const { deliveredAt } = comment;
	return (
		<li className={cn(INDENT, "flex min-w-0 items-start gap-2 py-1.5 text-sm")}>
			<Icon aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
			<div className="flex min-w-0 flex-1 flex-col">
				{comment.permalink === undefined ? (
					<span className="min-w-0 break-all">
						{label} <span className="text-xs text-muted-foreground">· no link recorded</span>
					</span>
				) : (
					<PageLink href={comment.permalink} allowedOrigin={pageOrigin} className="break-all">
						{label}
					</PageLink>
				)}
				<span className="text-xs text-muted-foreground">
					{comment.practices.length === 0 ? "Feedback" : comment.practices.join(", ")}
					{deliveredAt === undefined ? null : (
						<>
							{" · "}
							<time dateTime={deliveredAt} title={formatDateTime(deliveredAt)}>
								{formatRelative(deliveredAt, now)}
							</time>
						</>
					)}
				</span>
			</div>
		</li>
	);
}

/**
 * The comments Hephaestus recorded posting on the work for the reader, as the provider shows them.
 * The report's line already counts them, so the list carries no heading of its own; with none
 * recorded, it is not drawn at all.
 */
export function CommentList({ feedback, view, pageOrigin, now, onRetry }: CommentListProps) {
	if (feedback.status === "loading") {
		return <ListSkeleton />;
	}
	if (feedback.status === "error") {
		return <LoadError message={feedback.message} onRetry={onRetry} />;
	}
	const { comments, more } = feedback.data;
	if (comments.length === 0 && !more) {
		return null;
	}
	return (
		<div className="py-1">
			<ul aria-label="Comments for you" className="flex flex-col">
				{comments.toSorted(byView(view)).map((comment, index) => (
					<Comment
						// Comments carry no identity into the frame; the list is replaced whole on each answer.
						key={index}
						comment={comment}
						pageOrigin={pageOrigin}
						now={now}
					/>
				))}
			</ul>
			{more ? (
				<p className={cn(INDENT, "py-1 text-xs text-muted-foreground")}>
					Older comments are not listed here.
				</p>
			) : null}
		</div>
	);
}
