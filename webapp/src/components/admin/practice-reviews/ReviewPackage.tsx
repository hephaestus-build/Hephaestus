import { FileCode2Icon } from "lucide-react";
import type { GetPracticeReviewFeedbackResponse } from "@/api/types.gen";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { FeedbackBody } from "./FeedbackBody";

export function ReviewPackage({
	feedback,
	defaultExpanded = false,
}: {
	feedback: GetPracticeReviewFeedbackResponse;
	defaultExpanded?: boolean;
}) {
	const summary = feedback.proposedPlacements.find((placement) => placement.type === "SUMMARY");
	const inline = feedback.proposedPlacements.filter((placement) => placement.type === "INLINE");

	if (feedback.proposedPlacements.length === 0) {
		return (
			<p role="alert" className="rounded-lg border border-destructive/40 p-3 text-sm">
				This review package is unavailable.
			</p>
		);
	}

	return (
		<div className="min-w-0 space-y-3">
			{summary ? <FeedbackBody feedback={{ ...feedback, body: summary.body }} /> : null}
			{inline.length > 0 ? (
				<Accordion
					multiple
					defaultValue={defaultExpanded ? inline.map((_, index) => `inline-${index}`) : undefined}
					className="min-w-0 rounded-xl border px-4"
				>
					{inline.map((placement, index) => (
						<AccordionItem
							key={`${placement.path}:${placement.startLine}:${index}`}
							value={`inline-${index}`}
						>
							<AccordionTrigger className="gap-3 no-underline hover:no-underline">
								<span className="flex min-w-0 items-start gap-2">
									<FileCode2Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
									<span className="min-w-0">
										<span className="block font-mono text-xs break-all">{placement.path}</span>
										<span className="block text-xs font-normal text-muted-foreground">
											{placement.endLine !== undefined && placement.endLine !== placement.startLine
												? `Lines ${placement.startLine}–${placement.endLine}`
												: `Line ${placement.startLine}`}
										</span>
									</span>
								</span>
							</AccordionTrigger>
							<AccordionContent className="min-w-0 pb-4 pl-6">
								<div className={`${UNTRUSTED_MARKDOWN_PROSE} min-w-0 break-words`}>
									<UntrustedMarkdown>{placement.body}</UntrustedMarkdown>
								</div>
							</AccordionContent>
						</AccordionItem>
					))}
				</Accordion>
			) : null}
		</div>
	);
}
