import { RelativeTime } from "@/components/common/RelativeTime";
import { formatTime } from "@/lib/dates";

export interface DraftingProgressProps {
	/** When a model started to draft the report. Absent when no model drafts it. */
	since?: Date;
	/** About when the report goes on without a draft. */
	deadline?: Date;
	/** The model is busy with other work, so the draft waits for it. */
	waitingForModel: boolean;
	/** The report asks for a public issue, which a report without a draft never becomes. */
	wantsPublic: boolean;
	/** The reporter reads "your report"; an admin reads "the report". */
	reader: "reporter" | "admin";
}

/**
 * What a report in Drafting is doing and about when it goes on, so a slow draft never reads as
 * stuck. Without a model at work it says only that the issue is being prepared.
 */
export function DraftingProgress({
	since,
	deadline,
	waitingForModel,
	wantsPublic,
	reader,
}: DraftingProgressProps) {
	if (since === undefined) {
		return <p className="text-muted-foreground">Hephaestus prepares the issue from this report.</p>;
	}
	// Rounded up: the minute shown is never earlier than the deadline.
	const shownDeadline =
		deadline === undefined ? undefined : new Date(Math.ceil(deadline.getTime() / 60_000) * 60_000);
	return (
		<div className="flex flex-col gap-1 text-muted-foreground">
			<p>
				{waitingForModel
					? "The AI model is busy with other work, so the draft waits for it."
					: "An AI model is drafting the issue."}{" "}
				Started <RelativeTime value={since} />.
			</p>
			{shownDeadline !== undefined && (
				<p>
					If the draft is not ready by about{" "}
					<time dateTime={shownDeadline.toISOString()}>{formatTime(shownDeadline)}</time>,{" "}
					{reader === "reporter" ? "your report" : "the report"} goes on as written
					{wantsPublic ? " and does not become public." : "."}
				</p>
			)}
		</div>
	);
}
