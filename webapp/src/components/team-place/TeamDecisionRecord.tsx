import { ChevronDownIcon } from "lucide-react";
import type { ReactElement } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import { definitionFor } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { TEAM_DECISION_STATE_DEFS } from "@/components/practice-vocabulary/team-decision-state-defs";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { formatDateTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import type { ProblemDecisions, TeamDecisionData } from "./team-decisions";

/** Who recorded it and when. It names one member, never the team: one click is not the group's agreement. */
function recordedLine(decision: TeamDecisionData): string {
	const by = decision.decidedBy.former
		? "a former member"
		: (decision.decidedBy.displayName ?? "a former member");
	return `Recorded by ${by} · ${formatDateTime(decision.decidedAt)} · version ${decision.versionSequence}`;
}

function Note({ note }: { note?: string }) {
	if (!hasText(note)) {
		return null;
	}
	return (
		<p className="whitespace-pre-line text-foreground" dir="auto">
			{note}
		</p>
	);
}

export interface TeamDecisionRecordProps {
	decisions: ProblemDecisions;
	/** Opens this decision's entry in its thread, such as a router `Link`. Without it, no "Question this decision". */
	questionLink?: ReactElement;
}

/**
 * The current team decision on one problem, as every current member reads it: its state, the note in the recording
 * member's words, and who recorded it and when. The records it superseded stay readable, closed until opened.
 */
export function TeamDecisionRecord({ decisions, questionLink }: TeamDecisionRecordProps) {
	const { current, earlier } = decisions;
	return (
		<article
			aria-label={`Team decision: ${current.problemSummary}`}
			className="flex flex-col gap-2 rounded-lg border p-3 text-sm"
		>
			<div className="flex flex-wrap items-start justify-between gap-2">
				<div className="flex min-w-0 flex-col gap-0.5">
					<p className="font-medium text-foreground" dir="auto">
						{current.problemSummary}
					</p>
					<p className="text-muted-foreground">
						<bdi>{current.practiceName}</bdi>
					</p>
				</div>
				<StatusBadge def={definitionFor(TEAM_DECISION_STATE_DEFS, current.state)} />
			</div>
			<Note note={current.note} />
			<p className="text-muted-foreground">{recordedLine(current)}</p>
			{questionLink !== undefined && (
				<p>
					<InlineLink render={questionLink}>Question this decision</InlineLink>
				</p>
			)}
			{earlier.length > 0 && (
				<Collapsible>
					<CollapsibleTrigger
						render={
							<Button
								variant="ghost"
								size="xs"
								className="group -ml-2 w-fit text-muted-foreground"
							/>
						}
					>
						{earlier.length === 1 ? "One earlier record" : `${earlier.length} earlier records`}
						<ChevronDownIcon
							data-icon="inline-end"
							aria-hidden
							className="transition-transform group-data-[panel-open]:rotate-180"
						/>
					</CollapsibleTrigger>
					<CollapsibleContent>
						<ol className="mt-1 flex flex-col gap-2 border-l pl-3">
							{earlier.map((decision) => (
								<li key={decision.id} className="flex flex-col gap-1">
									<StatusBadge
										def={definitionFor(TEAM_DECISION_STATE_DEFS, decision.state)}
										className="w-fit"
									/>
									<Note note={decision.note} />
									<p className="text-muted-foreground">{recordedLine(decision)}</p>
								</li>
							))}
						</ol>
					</CollapsibleContent>
				</Collapsible>
			)}
		</article>
	);
}
