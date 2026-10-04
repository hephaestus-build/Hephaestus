import { cn } from "cn";

import type { EvidenceCitation, ObservationAnswers } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	codeCitationLocator,
	evidenceSourceDef,
} from "@/components/practice-vocabulary/evidence-source-defs";
import { hasText } from "@/lib/text";

import { QUESTION_ANSWER_DEFS } from "./question-answer-defs";

export interface ObservationAnswerListProps {
	answers: ObservationAnswers;
	/**
	 * The observation's citations, which each answer refers to by position. Given where the passages
	 * are shown beside the answers, so each answer can name the lines it rests on.
	 */
	citations?: readonly EvidenceCitation[];
	className?: string;
}

/**
 * How the review answered each question of the practice, the answers that decided the outcome first.
 * The deciding rule's reason is the observation's headline and is shown by the caller.
 */
export function ObservationAnswerList({
	answers,
	citations,
	className,
}: ObservationAnswerListProps) {
	const ordered = [
		...answers.answers.filter((answer) => answer.decisive),
		...answers.answers.filter((answer) => !answer.decisive),
	];
	return (
		<ul className={cn("flex min-w-0 flex-col gap-3 text-sm", className)}>
			{ordered.map((answer) => {
				const cited = citations
					? answer.citations.flatMap((index) => {
							const citation = citations[index];
							return citation === undefined ? [] : [locatorOf(citation)];
						})
					: [];
				return (
					<li key={answer.question} className="flex min-w-0 flex-col gap-1">
						<div className="flex min-w-0 flex-wrap items-center gap-2">
							<span className="font-medium text-pretty">{answer.title}</span>
							<StatusBadge def={QUESTION_ANSWER_DEFS[answer.answer]} />
							{answer.decisive && (
								<span className="text-xs text-muted-foreground">Decided the outcome</span>
							)}
						</div>
						<p className="text-pretty text-muted-foreground">{answer.because}</p>
						{hasText(answer.wouldSettleIt) && (
							<p className="text-pretty text-muted-foreground">
								<span className="text-foreground">Would settle it:</span> {answer.wouldSettleIt}
							</p>
						)}
						{answer.search && (
							<p className="text-pretty text-muted-foreground">
								<span className="text-foreground">Looked for:</span> {answer.search.lookedFor}.{" "}
								{answer.search.boundary}
							</p>
						)}
						{cited.length > 0 && (
							<p className="text-xs break-words text-muted-foreground">
								<span className="text-foreground">Cites:</span> {cited.join(" · ")}
							</p>
						)}
					</li>
				);
			})}
		</ul>
	);
}

/** A code passage by its file and lines; anything else by its source, since its lines are not the reader's. */
function locatorOf(citation: EvidenceCitation): string {
	const def = evidenceSourceDef(citation.sourceKind);
	return def.locator === "code" ? codeCitationLocator(citation) : def.label;
}
