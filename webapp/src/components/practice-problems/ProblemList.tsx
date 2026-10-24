import { cn } from "cn";
import type { ReactNode } from "react";

import type { Problem, ProblemSummary } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { hasText } from "@/lib/text";

/** A problem in full, or as a list read sends it: its severity and summary only. */
export type ProblemEntry = Problem | ProblemSummary;

export interface ProblemCardProps {
	problem: ProblemEntry;
	/**
	 * The passages that show the problem, drawn for the reader: the operator's evidence view or the
	 * developer's. Each surface keeps its own access rules, so the card only places what it is given.
	 */
	evidence?: ReactNode;
	/**
	 * The problem's severity badge, from the same registry on every surface: the operator ranks
	 * problems by it, and the developer reads which problem blocks the work and which is a small gap.
	 * "omitted" is for a surface that has no room for it.
	 */
	severity?: "shown" | "omitted";
	/**
	 * The level that shows this problem on its own. It takes the place of the level the card is on,
	 * which is a step in history, so Back returns there.
	 */
	open?: DetailStackEntry;
	/** Its place in the list, shown in a small circle before the summary where the list numbers them. */
	number?: number;
	/**
	 * Drawn as a card that stands off its ground, with the feedback card's edge and faint shadow: the
	 * developer's open observation, whose problems sit on a tinted body.
	 */
	raised?: boolean;
}

/** One problem under a Not met result: its severity, summary, explanation, evidence and next step. */
export function ProblemCard({
	problem,
	evidence,
	severity = "shown",
	open,
	number,
	raised = false,
}: ProblemCardProps) {
	const full = "citations" in problem ? problem : undefined;
	const summary = problem.summary.trim();
	return (
		<div
			className={cn(
				"flex min-w-0 flex-col gap-2 rounded-xl border bg-background p-3",
				raised && RAISED_CARD,
			)}
		>
			<div className="flex flex-wrap items-start gap-2">
				{number !== undefined && (
					// The list is an `ol`, so a screen reader counts it already. The number leads in its
					// circle, then the severity, then the summary: "1 Major The caching change hides…".
					<span
						aria-hidden
						data-problem-number=""
						className="flex size-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground tabular-nums"
					>
						{number}
					</span>
				)}
				{severity === "shown" && <StatusBadge def={SEVERITY_DEFS[problem.severity]} />}
				<p className="min-w-0 flex-1 basis-48 text-sm font-medium text-pretty break-words">
					{open === undefined ? (
						summary
					) : (
						<DetailStackLink entry={open} single className="underline-offset-4 hover:underline">
							{summary}
						</DetailStackLink>
					)}
				</p>
			</div>
			{hasText(full?.explanation) && <ProblemProse>{full.explanation}</ProblemProse>}
			{evidence}
			{hasText(full?.nextStep) && (
				<div className="space-y-1 border-t pt-2 text-sm">
					<p className="font-semibold text-muted-foreground">Next step</p>
					<ProblemProse>{full.nextStep}</ProblemProse>
				</div>
			)}
		</div>
	);
}

/** The feedback card's edge: a stronger outline and a faint shadow, so the card stands off its ground. */
const RAISED_CARD = "border-foreground/15 shadow-sm";

// The review writes a problem's explanation and next step in Markdown; the words take the
// foreground, so they read apart from the labels around them.
function ProblemProse({ children }: { children: string }) {
	return (
		<div className={cn(UNTRUSTED_MARKDOWN_PROSE, "text-sm text-pretty text-foreground")}>
			<UntrustedMarkdown>{children}</UntrustedMarkdown>
		</div>
	);
}

export interface ProblemListProps {
	problems: readonly ProblemEntry[];
	/** The evidence of one problem in full; a summary has none. */
	evidence?: (problem: Problem) => ReactNode;
	severity?: ProblemCardProps["severity"];
	/** The level of one problem, where the surface can open it. */
	open?: (problemId: string) => DetailStackEntry;
	/** Numbers the problems 1, 2, each in a small circle, in the order shown. */
	numbered?: boolean;
	/** Draws each problem as a raised card (`ProblemCardProps.raised`). */
	raised?: boolean;
}

function positionOf(problem: ProblemEntry): number {
	return "position" in problem ? problem.position : 0;
}

/** A result's problems in the order the review recorded them. */
export function ProblemList({
	problems,
	evidence,
	severity,
	open,
	numbered = false,
	raised = false,
}: ProblemListProps) {
	// A summary carries no position; the list read sends summaries in the recorded order already.
	const ordered = [...problems].sort((a, b) => positionOf(a) - positionOf(b));
	return (
		<ol aria-label="Problems" className="flex min-w-0 flex-col gap-2">
			{ordered.map((problem, index) => (
				<li key={problem.id} className="min-w-0">
					<ProblemCard
						problem={problem}
						evidence={"citations" in problem ? evidence?.(problem) : undefined}
						severity={severity}
						open={open?.(problem.id)}
						number={numbered ? index + 1 : undefined}
						raised={raised}
					/>
				</li>
			))}
		</ol>
	);
}
