import { Link } from "@tanstack/react-router";
import { MessageSquarePlus } from "lucide-react";
import { useId, useState } from "react";
import { definitionFor } from "@/components/common/status-def";

import type { EditProductFeedbackDraft, MyProductFeedback } from "@/api/types.gen";
import { FEEDBACK_KIND_DEFS } from "@/components/admin/product-feedback/feedback-kind-defs";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { hasText } from "@/lib/text";

import { DraftingProgress } from "./DraftingProgress";
import { FiledIssue } from "./FiledIssue";
import { ProductFeedbackDraftForm } from "./ProductFeedbackDraftForm";
import { PublicHoldNotice } from "./PublicHoldNotice";
import { REPORT_STATE_DEFS, REPORT_VISIBILITY_DEFS, reportVisibility } from "./report-state-defs";
import { ReportStatusLine } from "./ReportStatusLine";
import {
	type ProductFeedbackAction,
	reasonSentence,
	removedLine,
	SHARING_COPY,
} from "./sharing-copy";

export type MyProductFeedbackListState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; reports: MyProductFeedback[] };

export interface MyProductFeedbackListProps {
	state: MyProductFeedbackListState;
	/** The change in flight for each report, by id. */
	pending: Readonly<Record<string, ProductFeedbackAction>>;
	onKeepPrivate: (report: MyProductFeedback) => void;
	/** Resolves to whether the server took the edit. */
	onEdit: (report: MyProductFeedback, draft: EditProductFeedbackDraft) => Promise<boolean>;
	onMakePrivate: (report: MyProductFeedback) => void;
	onAllowPublicChange: (report: MyProductFeedback, allowPublic: boolean) => void;
}

const SKELETON_ROWS = 3;

/**
 * The person's own reports, newest first, as the server orders them. Each row offers only the
 * changes its flags allow, so every state keeps its reverse: held → Keep private or Edit, public →
 * Make private, private → Allow public and back.
 */
export function MyProductFeedbackList({ state, pending, ...actions }: MyProductFeedbackListProps) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load your reports"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status === "loading") {
		return (
			<ul className="flex flex-col gap-3" aria-busy>
				{Array.from({ length: SKELETON_ROWS }, (_, index) => (
					<li key={index}>
						<Card>
							<CardHeader>
								<Skeleton className="h-5 w-48" />
							</CardHeader>
							<CardContent className="flex flex-col gap-2">
								<Skeleton className="h-4 w-full" />
								<Skeleton className="h-4 w-2/3" />
							</CardContent>
						</Card>
					</li>
				))}
			</ul>
		);
	}
	if (state.reports.length === 0) {
		return (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<MessageSquarePlus />
					</EmptyMedia>
					<EmptyTitle>No reports yet</EmptyTitle>
					<EmptyDescription>
						Reports you send appear here with their status. Use Send feedback in the header to send
						one.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}
	return (
		<div className="flex flex-col gap-4">
			{state.reports.some((report) => report.canAllowPublic) && (
				<p className="max-w-prose text-sm text-muted-foreground">
					Allow public on a private report lets the Hephaestus maintainers publish it as a public
					issue. {SHARING_COPY.detail} Turning it on also makes public your default for new reports.
					Turning it off changes only that report. Change your default in{" "}
					<InlineLink
						render={<Link to="/settings" hash="product-feedback" />}
						className="underline"
					>
						your settings
					</InlineLink>
					.
				</p>
			)}
			<ul className="flex flex-col gap-3">
				{state.reports.map((report) => (
					<li key={report.id}>
						<MyProductFeedbackRow report={report} pending={pending[report.id]} {...actions} />
					</li>
				))}
			</ul>
		</div>
	);
}

interface RowProps extends Omit<MyProductFeedbackListProps, "state" | "pending"> {
	report: MyProductFeedback;
	pending: ProductFeedbackAction | undefined;
}

function MyProductFeedbackRow({
	report,
	pending,
	onKeepPrivate,
	onEdit,
	onMakePrivate,
	onAllowPublicChange,
}: RowProps) {
	const id = useId();
	const [editing, setEditing] = useState(false);
	const kind = definitionFor(FEEDBACK_KIND_DEFS, report.kind);
	const visibility = definitionFor(REPORT_VISIBILITY_DEFS, reportVisibility(report));
	const busy = pending !== undefined;
	const draft =
		report.canEdit && hasText(report.draftTitle)
			? { title: report.draftTitle, description: report.draftDescription ?? "" }
			: undefined;
	const where = issueLine(report);
	return (
		<Card>
			<CardHeader className="flex flex-wrap items-center gap-x-3 gap-y-1">
				{report.kind !== "FEEDBACK" && <StatusBadge def={kind} />}
				<StatusBadge def={definitionFor(REPORT_STATE_DEFS, report.state)} />
				<StatusBadge def={visibility} />
				<ReportStatusLine report={report} />
			</CardHeader>
			<CardContent className="flex flex-col gap-2">
				<p id={`${id}-message`} className="break-words whitespace-pre-wrap">
					{report.message}
				</p>
				{where !== undefined && <p className="text-muted-foreground">{where}</p>}
				{hasText(report.issueTitle) && hasText(report.issueText) && (
					<FiledIssue title={report.issueTitle} text={report.issueText} />
				)}
				{report.state === "DRAFTING" && (
					<DraftingProgress
						since={report.draftingSince}
						deadline={report.draftDeadline}
						waitingForModel={report.waitingForModel}
						wantsPublic={report.allowPublic}
						reader="reporter"
					/>
				)}
				<PublicHoldNotice
					state={report.state}
					filedAfter={report.filedAfter}
					wantsPublic={report.canKeepPrivate}
					reader="reporter"
				/>
				{hasText(report.reason) && (
					<p className="text-muted-foreground">
						{reasonSentence(report.reason, report.allowPublic)}
					</p>
				)}
				{hasText(report.removed) && (
					<p className="text-muted-foreground">{removedLine(report.removed)}</p>
				)}
				{report.canAllowPublic && (
					<Field orientation="horizontal" className="w-auto">
						<Switch
							id={`${id}-allow-public`}
							size="sm"
							checked={report.allowPublic}
							readOnly={busy}
							aria-describedby={`${id}-allow-public-detail ${id}-message`}
							onCheckedChange={(next) => onAllowPublicChange(report, next)}
						/>
						<FieldContent>
							<FieldLabel htmlFor={`${id}-allow-public`}>Allow public</FieldLabel>
							<FieldDescription id={`${id}-allow-public-detail`}>
								The maintainers can then publish it as a public issue.
							</FieldDescription>
						</FieldContent>
					</Field>
				)}
				{editing && draft !== undefined && (
					<ProductFeedbackDraftForm
						key={report.id}
						draft={draft}
						saving={pending === "edit"}
						onSave={async (next) => onEdit(report, next)}
						onCancel={() => setEditing(false)}
					/>
				)}
			</CardContent>
			{!editing && (report.canKeepPrivate || draft !== undefined || report.canMakePrivate) && (
				<CardFooter className="flex flex-wrap items-center gap-2">
					{report.canKeepPrivate && (
						<Button
							pending={pending === "keep-private"}
							type="button"
							variant="outline"
							size="sm"
							disabled={busy}
							aria-describedby={`${id}-message`}
							onClick={() => onKeepPrivate(report)}
						>
							{pending === "keep-private" ? "Keeping private…" : "Keep private"}
						</Button>
					)}
					{draft !== undefined && (
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={busy}
							aria-describedby={`${id}-message`}
							onClick={() => setEditing(true)}
						>
							Edit
						</Button>
					)}
					{report.canMakePrivate && (
						<AlertDialog>
							<AlertDialogTrigger
								render={
									<Button
										pending={pending === "make-private"}
										type="button"
										variant="outline"
										size="sm"
										disabled={busy}
										aria-describedby={`${id}-message`}
									>
										{pending === "make-private" ? "Making private…" : "Make private"}
									</Button>
								}
							/>
							<AlertDialogContent>
								<AlertDialogHeader>
									<AlertDialogTitle>Make this issue private?</AlertDialogTitle>
									<AlertDialogDescription>{SHARING_COPY.makePrivate}</AlertDialogDescription>
								</AlertDialogHeader>
								<AlertDialogFooter>
									<AlertDialogCancel>Cancel</AlertDialogCancel>
									<AlertDialogAction onClick={() => onMakePrivate(report)}>
										Make private
									</AlertDialogAction>
								</AlertDialogFooter>
							</AlertDialogContent>
						</AlertDialog>
					)}
				</CardFooter>
			)}
		</Card>
	);
}

/** Where a report is that is not on GitHub: only what its state already shows. */
function issueLine(report: MyProductFeedback): string | undefined {
	if (report.state === "SAVED_FOR_ADMINS") {
		return "Your report is saved on this instance for its admins. They can move it to GitHub as a private issue.";
	}
	return undefined;
}
