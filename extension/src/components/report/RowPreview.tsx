import { CircleAlertIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import { HephMark } from "~/components/brand/HephaestusLogo";
import { ExternalLink } from "~/components/common/ExternalLink";
import { where } from "~/components/report/comment-location";
import { PageLink } from "~/components/report/PageLink";
import {
	type Loadable,
	type ReportState,
	summarizeReport,
} from "~/components/report/report-summary";
import { LIST_STRIP_HEIGHT } from "~/shared/frame-messages";
import type { ReviewActivity, WorkFeedback } from "~/shared/review-context";

export interface RowPreviewProps {
	state: ReportState;
	/** The reader's own comments on the row's work. */
	feedback?: Loadable<WorkFeedback>;
	activity: ReviewActivity | undefined;
	/** Where a link into Hephaestus may point. */
	webAppOrigin: string | undefined;
	onRetry: () => void;
	onOpenSettings: () => void;
	/** A refresh failed while an earlier answer is shown: it is said, not passed off as current. */
	stale?: { message: string };
}

/** A list row has room for this many comment links beside its sentence; the rest are counted. */
export const ROW_COMMENT_LINKS = 2;

/** A text-sized control, as the row's own meta line would have one. */
function TextButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
	return (
		<button
			type="button"
			onClick={onClick}
			className="text-link shrink-0 rounded-sm font-medium underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
		>
			{children}
		</button>
	);
}

/**
 * The reader's first comments on the row's work, each a link to it on the work's page. A link shows
 * only where the row has room for it whole — the first from a medium row, the second from a wide
 * one — so no link is ever cut off under a keyboard's focus; a narrow row keeps the sentence alone.
 */
function CommentLinks({ feedback, pageOrigin }: { feedback: WorkFeedback; pageOrigin: string }) {
	const shown = feedback.comments.slice(0, ROW_COMMENT_LINKS);
	const rest = feedback.comments.length - shown.length;
	if (shown.length === 0) {
		return null;
	}
	return (
		<>
			{shown.map((comment, index) => (
				<span
					key={index}
					className={cn(
						"min-w-0 shrink-0 items-center gap-2",
						index === 0 ? "hidden @min-[32rem]:flex" : "hidden @min-[44rem]:flex",
					)}
				>
					<span aria-hidden>·</span>
					<span className="max-w-48 truncate" title={where(comment, false)}>
						{comment.permalink === undefined ? (
							where(comment, true)
						) : (
							<PageLink href={comment.permalink} allowedOrigin={pageOrigin}>
								{where(comment, true)}
							</PageLink>
						)}
					</span>
				</span>
			))}
			{rest > 0 ? <span className="hidden shrink-0 @min-[44rem]:inline">+{rest}</span> : null}
		</>
	);
}

/**
 * A list row's preview, for triage: one quiet line inside the row, under its own title and meta,
 * shown in full the moment the reader presses the row's Hephaestus button — the same sentence the
 * work's page leads with, and the way to the first comments. No card, no heading, nothing to open:
 * the row's title opens the work, where the rest is. It only reads; asking for a review is the work
 * page's. The line is one height in every state, so the page learns nothing from its size.
 */
export function RowPreview({
	state,
	feedback,
	activity,
	webAppOrigin,
	onRetry,
	onOpenSettings,
	stale,
}: RowPreviewProps) {
	const summary = summarizeReport({ state, feedback, activity, readOnly: true });
	let action: ReactNode = null;
	// The row has no list to put a failed part's retry in, nor a failed refresh's; the line carries it.
	switch (feedback?.status === "error" || stale !== undefined ? "retry" : summary.action) {
		case "retry": {
			action = <TextButton onClick={onRetry}>Try again</TextButton>;
			break;
		}
		case "set-up":
		case "sign-in": {
			action = (
				<TextButton onClick={onOpenSettings}>
					{summary.action === "set-up" ? "Set up" : "Sign in"}
				</TextButton>
			);
			break;
		}
		case "finish-in-app": {
			action =
				state.status === "consent-required" && webAppOrigin !== undefined ? (
					<ExternalLink href={state.webAppUrl} allowedOrigin={webAppOrigin} tone="link">
						Open Hephaestus
					</ExternalLink>
				) : null;
			break;
		}
		case "request-review":
		case undefined: {
			break;
		}
	}
	const pageOrigin = state.status === "ready" ? new URL(state.pageUrl).origin : undefined;
	return (
		<div
			className="@container flex min-w-0 items-center gap-2 overflow-hidden text-xs whitespace-nowrap text-muted-foreground"
			style={{ minHeight: LIST_STRIP_HEIGHT }}
		>
			{summary.tone === "error" || stale !== undefined ? (
				<CircleAlertIcon
					aria-hidden
					className={cn(
						"size-3.5 shrink-0",
						stale === undefined ? "text-destructive" : "text-warning",
					)}
				/>
			) : (
				<HephMark className="size-3.5" />
			)}
			<p role="status" className="m-0 min-w-0 shrink truncate" title={stale?.message}>
				<span className="sr-only">Practice review: </span>
				{stale === undefined ? null : (
					<span className="text-foreground">
						Not refreshed
						<span className="sr-only">: {stale.message} Last answer:</span>
						<span aria-hidden> · </span>
					</span>
				)}
				<span className={summary.tone === "error" ? "text-foreground" : undefined}>
					{summary.text}
				</span>
			</p>
			{feedback?.status === "ready" && pageOrigin !== undefined ? (
				<CommentLinks feedback={feedback.data} pageOrigin={pageOrigin} />
			) : null}
			{action === null ? null : <span className="shrink-0">{action}</span>}
		</div>
	);
}
