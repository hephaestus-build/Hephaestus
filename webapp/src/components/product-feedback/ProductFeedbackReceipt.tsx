import { useState, type ReactNode } from "react";

import type { EditProductFeedbackDraft, MyProductFeedback } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { hasText } from "@/lib/text";

import { ProductFeedbackDraftForm } from "./ProductFeedbackDraftForm";
import {
	holdCountdown,
	type ProductFeedbackAction,
	reasonSentence,
	removedLine,
} from "./sharing-copy";

/** What the confirmation reads from a report: the answer to Send at first, then the report itself. */
export type ReceiptReport = Pick<
	MyProductFeedback,
	| "id"
	| "state"
	| "route"
	| "reason"
	| "filedAfter"
	| "issueUrl"
	| "issueNumber"
	| "removed"
	| "draftTitle"
	| "draftDescription"
	| "canKeepPrivate"
	| "canEdit"
>;

export interface ProductFeedbackReceiptProps {
	report: ReceiptReport;
	/** `owner/name` of the public repository, which the hold names. */
	publicRepository?: string;
	pending?: ProductFeedbackAction;
	onKeepPrivate: () => void;
	/** Resolves to whether the server took the edit. */
	onEdit: (draft: EditProductFeedbackDraft) => Promise<boolean>;
}

/**
 * Says where the report is, in the words its state allows and no further: "public" and "filed"
 * appear only once the state says so. While a public report waits, it counts down to the filing
 * and offers Keep private and, once the draft is ready, Edit.
 */
export function ProductFeedbackReceipt({
	report,
	publicRepository,
	pending,
	onKeepPrivate,
	onEdit,
}: ProductFeedbackReceiptProps) {
	const [editing, setEditing] = useState(false);
	const held = report.state === "HELD";
	const draft =
		held && hasText(report.draftTitle)
			? { title: report.draftTitle, description: report.draftDescription ?? "" }
			: undefined;
	return (
		<div className="flex flex-col gap-4">
			{/* Present from the first render, so each later change of state is announced. */}
			<div aria-live="polite" className="flex flex-col gap-2">
				<ReceiptStatement report={report} publicRepository={publicRepository} />
				{report.state === "DRAFTING" && (
					<p className="text-sm text-muted-foreground">Drafting the issue…</p>
				)}
			</div>
			{hasText(report.removed) && (
				<p className="text-sm text-muted-foreground">{removedLine(report.removed)}</p>
			)}
			{draft !== undefined &&
				(editing ? (
					<ProductFeedbackDraftForm
						key={report.id}
						draft={draft}
						saving={pending === "edit"}
						onSave={onEdit}
						onCancel={() => setEditing(false)}
					/>
				) : (
					<section aria-label="The drafted issue" className="rounded-lg border p-3 text-sm">
						<p className="font-medium break-words">{draft.title}</p>
						<p className="mt-1 break-words whitespace-pre-wrap text-muted-foreground">
							{draft.description}
						</p>
					</section>
				))}
			{!editing && (report.canKeepPrivate || (report.canEdit && draft !== undefined)) && (
				<div className="flex flex-wrap gap-2">
					{report.canKeepPrivate && (
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={pending !== undefined}
							onClick={onKeepPrivate}
						>
							{pending === "keep-private" && <Spinner />}
							{pending === "keep-private" ? "Keeping private…" : "Keep private"}
						</Button>
					)}
					{report.canEdit && draft !== undefined && (
						<Button
							type="button"
							variant="outline"
							size="sm"
							disabled={pending !== undefined}
							onClick={() => setEditing(true)}
						>
							Edit
						</Button>
					)}
				</div>
			)}
		</div>
	);
}

function ReceiptStatement({
	report,
	publicRepository,
}: {
	report: ReceiptReport;
	publicRepository?: string;
}) {
	const now = useNow();
	const reason = hasText(report.reason) ? ` ${reasonSentence(report.reason)}` : "";
	let statement: ReactNode;
	if (report.state === "SENT_TO_ADMINS") {
		statement = "Thanks. The admins of this instance have it.";
	} else if (report.state === "STOPPED") {
		statement =
			"Thanks. It’s saved. We could not file it on GitHub, so the admins of this instance have it and can try again.";
	} else if (report.state === "FILED") {
		statement = hasText(report.issueUrl) ? (
			<>
				Thanks. It’s a public issue on GitHub:{" "}
				<InlineLink href={report.issueUrl} external className="underline">
					{report.issueNumber === undefined ? "Open the issue" : `Issue #${report.issueNumber}`}
				</InlineLink>
			</>
		) : (
			`The maintainers have it as a private issue on GitHub.${reason}`
		);
	} else if (report.state === "FILING") {
		statement = "Thanks. It’s saved. Hephaestus is filing it on GitHub now.";
	} else if (report.route === "PUBLIC") {
		const where = hasText(publicRepository) ? publicRepository : "the public repository";
		const when =
			report.filedAfter === undefined
				? "in about 5 minutes"
				: holdCountdown(report.filedAfter, now);
		statement = `Thanks. It’s saved. Unless a check keeps it private, it becomes a public issue in ${where} ${when}.`;
	} else if (report.route === "PRIVATE") {
		statement = `Thanks. It’s saved. The maintainers get it as a private issue on GitHub.${reason}`;
	} else {
		statement = `Thanks. It’s saved. The admins of this instance get it.${reason}`;
	}
	return <p className="text-sm break-words">{statement}</p>;
}
