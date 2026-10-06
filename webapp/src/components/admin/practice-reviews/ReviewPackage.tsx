import { FileCode2Icon } from "lucide-react";

import type { GetPracticeReviewFeedbackResponse, ReviewProposedPlacement } from "@/api/types.gen";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import { PLACEMENT_DEFS } from "@/components/practice-vocabulary/placement-defs";
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
	const inline = feedback.proposedPlacements.filter(isInline);

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
											{placement.type === "LOCATION_COMMENT"
												? ` · ${PLACEMENT_DEFS.LOCATION_COMMENT.label}`
												: null}
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

type InlinePlacement = ReviewProposedPlacement & {
	type: "INLINE" | "LOCATION_COMMENT";
	path: string;
	startLine: number;
};

/**
 * A placement about lines: proposed on them, or recorded as a comment linking to them. Either keeps its file
 * and first line; the summary has neither.
 */
function isInline(placement: ReviewProposedPlacement): placement is InlinePlacement {
	return (
		(placement.type === "INLINE" || placement.type === "LOCATION_COMMENT") &&
		placement.path !== undefined &&
		placement.startLine !== undefined
	);
}
