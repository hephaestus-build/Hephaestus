import { FileCode2Icon } from "lucide-react";
import { definitionFor } from "@/components/common/status-def";

import type { FeedbackCopy, ReviewFeedbackDetail } from "@/api/types.gen";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import {
	FEEDBACK_COPY_KIND_DEFS,
	FEEDBACK_COPY_STATE_DEFS,
} from "@/components/practice-vocabulary/feedback-copy-defs";
import { ADMIN_WITHHELD_TEXT } from "@/components/practice-vocabulary/feedback-destination-defs";
import { StatusLabel } from "@/components/practice-vocabulary/StatusTooltip";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";

import { FeedbackBody } from "./FeedbackBody";

export interface ReviewPackageProps {
	feedback: Pick<ReviewFeedbackDetail, "destination" | "copies" | "body" | "textHidden">;
	defaultExpanded?: boolean;
}

// Private destinations must hide copy text and counts even when the caller supplies them.
export function ReviewPackage({ feedback, defaultExpanded = false }: ReviewPackageProps) {
	if (feedback.textHidden || ADMIN_WITHHELD_TEXT[feedback.destination] !== undefined) {
		return <FeedbackBody feedback={feedback} />;
	}
	const copies = [...feedback.copies].sort((a, b) => a.position - b.position);
	const summaries = copies.filter((copy) => copy.kind === "SUMMARY");
	const notes = copies.filter(isNote);

	return (
		<div className="min-w-0 space-y-3">
			{summaries.map((summary) => (
				<div key={summary.id} className="min-w-0 space-y-1.5">
					<StatusLabel
						def={definitionFor(FEEDBACK_COPY_STATE_DEFS, summary.state)}
						className="text-xs text-muted-foreground"
					/>
					<FeedbackBody feedback={{ ...feedback, body: summary.body ?? feedback.body }} />
				</div>
			))}
			{notes.length > 0 ? (
				<Accordion
					multiple
					defaultValue={defaultExpanded ? notes.map((note) => note.id) : undefined}
					className="min-w-0 rounded-xl border px-4"
				>
					{notes.map((note) => (
						<AccordionItem key={note.id} value={note.id}>
							{/* One step below the sheet section the package stands in. */}
							<AccordionTrigger headingLevel={4} className="gap-3 no-underline hover:no-underline">
								<span className="flex min-w-0 items-start gap-2">
									<FileCode2Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
									<span className="min-w-0">
										<span className="block font-mono text-xs break-all">{note.path}</span>
										<span className="block text-xs font-normal text-muted-foreground">
											{noteAnchor(note)}
										</span>
										<StatusLabel
											def={definitionFor(FEEDBACK_COPY_STATE_DEFS, note.state)}
											className="text-xs font-normal"
										/>
									</span>
								</span>
							</AccordionTrigger>
							<AccordionContent className="min-w-0 pb-4 pl-6">
								<div className={`${UNTRUSTED_MARKDOWN_PROSE} min-w-0 break-words`}>
									<UntrustedMarkdown>{note.body ?? ""}</UntrustedMarkdown>
								</div>
							</AccordionContent>
						</AccordionItem>
					))}
				</Accordion>
			) : null}
		</div>
	);
}

type NoteCopy = FeedbackCopy & { kind: "LINE_NOTE" | "FILE_NOTE"; path: string };

function isNote(copy: FeedbackCopy): copy is NoteCopy {
	return (copy.kind === "LINE_NOTE" || copy.kind === "FILE_NOTE") && copy.path !== null;
}

function noteAnchor(note: NoteCopy): string {
	if (note.kind === "FILE_NOTE" || note.startLine === null) {
		return FEEDBACK_COPY_KIND_DEFS.FILE_NOTE.label;
	}
	return note.endLine !== null && note.endLine !== note.startLine
		? `Lines ${note.startLine}–${note.endLine}`
		: `Line ${note.startLine}`;
}
