import { statusValues } from "@/components/common/status-def";
import {
	MEMBER_AI_CHOICE_DEFS,
	type MemberAiChoice,
} from "@/components/practice-vocabulary/data-handling-defs";
import {
	QuestionnaireChoice,
	QuestionnaireChoiceDescription,
	QuestionnaireChoices,
} from "@/components/ui/questionnaire";

export const AI_CHOICES = statusValues(MEMBER_AI_CHOICE_DEFS);

export interface AiChoiceCardsProps {
	/** The checked card; `undefined` checks nothing, so a first answer is never pre-selected. */
	choice: MemberAiChoice | undefined;
	onChoice: (choice: MemberAiChoice) => void;
}

/**
 * The four answers as equal cards inside a `QuestionnaireItem` the caller owns: the same icon size,
 * the same two headings, nothing pre-selected and no workspace configuration. What a workspace has
 * set up under an answer is one sentence the caller places below the grid, so the cards read the
 * same on the setup page and in User settings.
 */
export function AiChoiceCards({ choice, onChoice }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 sm:grid-cols-2">
			{AI_CHOICES.map((value) => {
				const { icon: Icon, label, description, consideration } = MEMBER_AI_CHOICE_DEFS[value];
				return (
					<QuestionnaireChoice
						key={value}
						value={value}
						checked={choice === value}
						onChange={(event) => {
							if (event.target.checked) {
								onChoice(value);
							}
						}}
					>
						<span className="mb-2 flex items-center gap-2 font-medium">
							<Icon className="size-5 shrink-0 text-mentor" aria-hidden="true" />
							{label}
						</span>{" "}
						<QuestionnaireChoiceDescription>
							<span className="block">
								<span className="font-medium text-foreground">Allows</span> {description}
							</span>{" "}
							<span className="mt-3 block">
								<span className="font-medium text-foreground">Consider</span> {consideration}
							</span>
						</QuestionnaireChoiceDescription>
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}
