import type { ReactElement } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import { definitionFor } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { REVISIT_FINDING_DEFS } from "@/components/practice-vocabulary/revisit-finding-defs";

import type { TeamAnchor, TeamRevisit as TeamRevisitData } from "./team-place-entries";
import { TeamCitations } from "./TeamCitations";

export interface TeamRevisitProps {
	revisit: TeamRevisitData;
	anchorLink?: (anchor: TeamAnchor) => ReactElement;
	/** Opens the decision record it revisits, which holds the note and who recorded it. */
	decisionLink?: ReactElement;
}

/**
 * What a later review shows about a decision the team recorded: a finding, Hephaestus's statement and the places in the
 * new version it rests on. It leaves the next decision to the team.
 */
export function TeamRevisit({ revisit, anchorLink, decisionLink }: TeamRevisitProps) {
	return (
		<section
			aria-label="A recorded decision, revisited"
			className="flex flex-col gap-2 rounded-lg border p-3 text-sm"
		>
			<div className="flex flex-wrap items-center gap-2">
				<p className="font-medium text-foreground">A recorded decision, revisited</p>
				<StatusBadge def={definitionFor(REVISIT_FINDING_DEFS, revisit.finding)} />
			</div>
			<p className="text-foreground" dir="auto">
				{revisit.statement}
			</p>
			<TeamCitations citations={revisit.citations} anchorLink={anchorLink} />
			{decisionLink !== undefined && (
				<p>
					<InlineLink render={decisionLink}>Open the decision</InlineLink>
				</p>
			)}
		</section>
	);
}
