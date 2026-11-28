import type { ReactElement } from "react";

import { Button } from "@/components/ui/button";

import type { TeamAnchor, TeamChoicePoint as TeamChoicePointData } from "./team-place-entries";
import { TeamCitations } from "./TeamCitations";

export interface TeamChoicePointProps {
	choicePoint: TeamChoicePointData;
	anchorLink?: (anchor: TeamAnchor) => ReactElement;
	/**
	 * Opens the member's form to record the team's decision on this problem, with the prompt and any draft note. Without
	 * it, the options show alone.
	 */
	onRecordDecision?: (choicePoint: TeamChoicePointData) => void;
}

/**
 * The options Hephaestus proposes where a review leaves the team a real choice on one problem. They are proposals:
 * choosing one records nothing, and only a member's own Record writes the team's decision.
 */
export function TeamChoicePoint({
	choicePoint,
	anchorLink,
	onRecordDecision,
}: TeamChoicePointProps) {
	return (
		<section
			aria-label="A choice for the team"
			className="flex flex-col gap-2 rounded-lg border p-3 text-sm"
		>
			<p className="font-medium text-foreground">A choice for the team</p>
			<ul className="list-disc pl-5">
				{choicePoint.options.map((option) => (
					<li key={option.id} dir="auto">
						{option.text}
					</li>
				))}
			</ul>
			<TeamCitations citations={choicePoint.citations} anchorLink={anchorLink} />
			<p className="text-muted-foreground">
				Hephaestus proposes these options. A member records the team’s decision.
			</p>
			{onRecordDecision !== undefined && (
				<Button
					variant="outline"
					size="sm"
					className="w-fit"
					onClick={() => onRecordDecision(choicePoint)}
				>
					Record a decision
				</Button>
			)}
		</section>
	);
}
