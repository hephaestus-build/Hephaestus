import { type ReactNode, useEffect, useId, useRef, useState } from "react";

import { cn } from "cn";
import { Button } from "~/components/common/Button";
import { ExternalLink } from "~/components/common/ExternalLink";
import { formatDateTime, formatTime } from "~/components/common/format";
import { CommentList } from "~/components/report/CommentList";
import { ObservationList } from "~/components/report/ObservationList";
import { INDENT, ListSkeleton } from "~/components/report/report-parts";
import {
	type Loadable,
	type ReportState,
	type ReportSummary,
	reviewedAt,
	reviewedRevision,
	summarizeReport,
} from "~/components/report/report-summary";
import { ReportRow } from "~/components/report/ReportRow";
import { WorkspaceChooser } from "~/components/report/WorkspaceChooser";
import type { ReviewAction } from "~/shared/review-actions";
import type {
	ObservationPage,
	ReadyContext,
	ReviewActivity,
	WorkFeedback,
} from "~/shared/review-context";

export type ActionState = { status: "pending" } | { status: "error"; message: string };

export interface PageReportProps {
	state: ReportState;
	/** The reader's own comments on the work. */
	feedback?: Loadable<WorkFeedback>;
	/** The reader's own observations, asked for once the report is open. */
	observations?: Loadable<ObservationPage>;
	/** What the server says is happening to a review now (`reviewActivity`), if anything. */
	activity: ReviewActivity | undefined;
	/** Where links into Hephaestus may point; nothing else becomes such a link. */
	webAppOrigin: string | undefined;
	expanded: boolean;
	onToggle: () => void;
	onRetry: () => void;
	onRetryObservations: () => void;
	onOpenSettings: () => void;
	onChooseWorkspace: (slug: string) => void;
	/** Opens the extension's confirmation window; nothing changes in the page. */
	onAction: (action: ReviewAction) => void;
	/** What became of the last request to open the confirmation window. */
	action?: ActionState;
	/** A refresh failed while an earlier answer is on screen. */
	stale?: { message: string };
}

/** When the work was last reviewed, precisely, and at which commit: the line says how long ago. */
function reviewedSentence(context: ReadyContext): string {
	const reviewed = reviewedAt(context);
	if (context.trace === null) {
		return "No review recorded.";
	}
	if (reviewed === undefined) {
		return "No completed practice review recorded.";
	}
	const revision = reviewedRevision(context);
	return `Reviewed ${formatDateTime(reviewed) ?? reviewed}${revision === undefined ? "" : ` at ${revision}`}.`;
}

/**
 * The opened report's last line: when, precisely, and where the rest is — the review's history and
 * evidence in Hephaestus, and for a workspace admin every developer's records and the review's runs.
 */
function Footer({
	context,
	webAppOrigin,
	request,
}: {
	context: ReadyContext;
	webAppOrigin: string;
	request: ReactNode;
}) {
	return (
		<div
			className={cn(
				INDENT,
				"flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-border py-2 text-xs text-muted-foreground",
			)}
		>
			<span className="min-w-0 basis-full @lg:mr-auto @lg:basis-auto">
				{reviewedSentence(context)}
			</span>
			{request}
			<ExternalLink href={context.links.trace} allowedOrigin={webAppOrigin} tone="link">
				Open in Hephaestus
			</ExternalLink>
			{context.links.reviewDetails === undefined ? null : (
				<ExternalLink href={context.links.reviewDetails} allowedOrigin={webAppOrigin} tone="link">
					Review details
				</ExternalLink>
			)}
		</div>
	);
}

/**
 * No observation beside comments that are there says nothing worth a section; with no comments either,
 * it is the one fact the opened report has, and it stays. Loading and failure always show.
 */
function quietlyEmpty(
	observations: Loadable<ObservationPage>,
	feedback: Loadable<WorkFeedback> | undefined,
): boolean {
	return (
		observations.status === "ready" &&
		observations.data.rows.length === 0 &&
		feedback?.status === "ready" &&
		feedback.data.comments.length > 0
	);
}

function Ready({
	context,
	props,
	webAppOrigin,
}: {
	context: ReadyContext;
	props: PageReportProps;
	webAppOrigin: string;
}) {
	const { feedback, observations, activity, action, stale } = props;
	const offer = context.canRequestReview && activity !== "queued-or-running";
	return (
		<>
			{stale === undefined ? null : (
				<p role="status" className={cn(INDENT, "border-t border-border py-2 text-xs text-warning")}>
					Not refreshed: {stale.message} Showing what Hephaestus said at{" "}
					<time dateTime={context.fetchedAt}>{formatTime(context.fetchedAt)}</time>.
				</p>
			)}
			{context.alternatives.length > 0 ? (
				<div className={cn(INDENT, "border-t border-border py-2.5")}>
					<WorkspaceChooser
						choices={[context.workspace, ...context.alternatives]}
						value={context.workspace.slug}
						onChange={props.onChooseWorkspace}
					/>
				</div>
			) : null}
			{feedback === undefined ? null : (
				<div className="border-t border-border">
					<CommentList
						feedback={feedback}
						view={context.view}
						pageOrigin={new URL(context.pageUrl).origin}
						now={context.fetchedAt}
						onRetry={props.onRetry}
					/>
				</div>
			)}
			{observations === undefined || quietlyEmpty(observations, feedback) ? null : (
				<ObservationList observations={observations} onRetry={props.onRetryObservations} />
			)}
			<Footer
				context={context}
				webAppOrigin={webAppOrigin}
				request={
					offer ? (
						// The line carries the request; only where the line is too narrow for it does it move here.
						<Button
							variant="outline"
							size="sm"
							className="hidden @max-[36rem]:inline-flex"
							disabled={action?.status === "pending"}
							onClick={() => props.onAction({ kind: "request-review" })}
						>
							Request review…
						</Button>
					) : null
				}
			/>
			{action?.status === "error" ? (
				<p role="alert" className={cn(INDENT, "pb-2 text-xs text-destructive")}>
					{action.message}
				</p>
			) : null}
		</>
	);
}

function OpenApp({ href, webAppOrigin }: { href: string; webAppOrigin: string }) {
	return (
		<ExternalLink
			href={href}
			allowedOrigin={webAppOrigin}
			button={{ variant: "outline", size: "sm" }}
		>
			Open Hephaestus
		</ExternalLink>
	);
}

/** What the opened report says when there is no review to show, and the one thing to do. */
function Explanation({ props, webAppOrigin }: { props: PageReportProps; webAppOrigin: string }) {
	const { state } = props;
	const app =
		webAppOrigin === "" ? null : <OpenApp href={webAppOrigin} webAppOrigin={webAppOrigin} />;
	let text: ReactNode;
	let next: ReactNode = null;
	switch (state.status) {
		case "loading": {
			return <ListSkeleton />;
		}
		// The line offers Try again; the explanation says what failed and does not repeat the control.
		case "failed":
		case "error": {
			return (
				<p
					role="alert"
					className={cn(INDENT, "border-t border-border py-3 text-sm text-muted-foreground")}
				>
					{state.message}
				</p>
			);
		}
		// These three states' action is on the line itself; the explanation does not repeat it.
		case "not-configured": {
			text = "Connect the extension to Hephaestus and sign in to see practice reviews here.";
			break;
		}
		case "signed-out": {
			text = `Sign in to ${state.instanceHost} to see the practice review of this work.`;
			break;
		}
		case "consent-required": {
			text = "Read and accept the current notice in the Hephaestus web app, then come back.";
			break;
		}
		case "no-workspace": {
			text = `None of your workspaces on ${state.instanceHost} is connected to ${state.siteOrigin}.`;
			next = app;
			break;
		}
		case "not-found": {
			text = `${state.workLabel} is not work any of your workspaces follows, or not work you can see. Hephaestus does not say which.`;
			next = app;
			break;
		}
		case "unsupported-page": {
			text = "Practice reviews appear on a single pull request, merge request or issue.";
			break;
		}
		case "choose-workspace": {
			return (
				<div className={cn(INDENT, "border-t border-border py-2.5")}>
					<WorkspaceChooser
						choices={state.candidates}
						value={undefined}
						onChange={props.onChooseWorkspace}
					/>
				</div>
			);
		}
		case "ready": {
			return null;
		}
	}
	return (
		<div
			className={cn(
				INDENT,
				"flex flex-wrap items-center gap-x-3 gap-y-2 border-t border-border py-3",
			)}
		>
			<p className="min-w-0 flex-1 basis-60 text-sm text-muted-foreground">{text}</p>
			{next}
		</div>
	);
}

/** The line's one next step beside it, when its state has one. */
function LineAction({
	summary,
	props,
	webAppOrigin,
}: {
	summary: ReportSummary;
	props: PageReportProps;
	webAppOrigin: string;
}) {
	const { state } = props;
	switch (summary.action) {
		case "retry": {
			return (
				<Button variant="outline" size="sm" onClick={props.onRetry}>
					Try again
				</Button>
			);
		}
		case "set-up":
		case "sign-in": {
			return (
				<Button variant="outline" size="sm" onClick={props.onOpenSettings}>
					{summary.action === "set-up" ? "Set up" : "Sign in"}
				</Button>
			);
		}
		case "finish-in-app": {
			return state.status === "consent-required" ? (
				<OpenApp href={state.webAppUrl} webAppOrigin={webAppOrigin} />
			) : null;
		}
		case "request-review": {
			// Folded into the opened report when the line is too narrow for both.
			return (
				<Button
					variant="outline"
					size="sm"
					className="@max-[36rem]:hidden"
					disabled={props.action?.status === "pending"}
					onClick={() => props.onAction({ kind: "request-review" })}
				>
					Request review…
				</Button>
			);
		}
		case undefined: {
			return null;
		}
	}
}

/**
 * Speaks the line when it changes after it first settled — a review finishing, feedback arriving —
 * and not on every page load, which would read the report aloud before the reader reached it.
 */
function useAnnouncement(text: string, settled: boolean): string {
	const first = useRef<string | undefined>(undefined);
	const [announcement, setAnnouncement] = useState("");
	useEffect(() => {
		if (!settled) {
			return;
		}
		if (first.current === undefined) {
			first.current = text;
		} else if (first.current !== text) {
			first.current = text;
			setAnnouncement(`Practice review: ${text}`);
		}
	}, [text, settled]);
	return announcement;
}

/**
 * The practice review on the work's own page, as the provider's merge request reports are built: one
 * line — what is happening, the reader's comments, when the work was reviewed — that opens, and only
 * it opens, into flat lists: the way to each comment, then what the review concluded about the
 * reader's work, then where the rest is. Nothing inside opens further. The line is the same height in
 * every state; only the reader's own expansion changes the report's size.
 */
export function PageReport(props: PageReportProps) {
	const { state, feedback, activity, expanded, onToggle } = props;
	const detailsId = useId();
	const webAppOrigin = props.webAppOrigin ?? "";
	const summary = summarizeReport({ state, feedback, activity });
	const settled =
		state.status !== "loading" &&
		(state.status !== "ready" || (feedback !== undefined && feedback.status !== "loading"));
	const announcement = useAnnouncement(summary.text, settled);
	return (
		<div className="@container bg-card text-card-foreground">
			<ReportRow
				summary={summary}
				expanded={expanded}
				controls={detailsId}
				onToggle={onToggle}
				action={<LineAction summary={summary} props={props} webAppOrigin={webAppOrigin} />}
			/>
			{expanded ? (
				<div id={detailsId}>
					{state.status === "ready" ? (
						<Ready context={state} props={props} webAppOrigin={webAppOrigin} />
					) : (
						<Explanation props={props} webAppOrigin={webAppOrigin} />
					)}
				</div>
			) : null}
			<p className="sr-only" aria-live="polite">
				{announcement}
			</p>
		</div>
	);
}
