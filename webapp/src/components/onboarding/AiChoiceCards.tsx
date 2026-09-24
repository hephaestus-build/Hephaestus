import { statusValues } from "@/components/common/status-def";
import {
	MEMBER_AI_CHOICE_DEFS,
	type MemberAiChoice,
} from "@/components/practice-vocabulary/data-handling-defs";
import { Badge } from "@/components/ui/badge";
import {
	QuestionnaireChoice,
	QuestionnaireChoiceDescription,
	QuestionnaireChoices,
} from "@/components/ui/questionnaire";

export const AI_CHOICES = statusValues(MEMBER_AI_CHOICE_DEFS);

export interface AiChoiceCardsProps {
	choice: MemberAiChoice | undefined;
	saved?: MemberAiChoice;
	onChoice: (choice: MemberAiChoice) => void;
}

export function AiChoiceCards({ choice, saved, onChoice }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 md:grid-cols-3">
			{AI_CHOICES.map((value) => {
				const { label, description, consequence, icon: Icon } = MEMBER_AI_CHOICE_DEFS[value];
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
						className="h-full"
					>
						<span className="flex min-w-0 items-center gap-2 font-semibold text-foreground">
							<Icon aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
							{label} {saved === value && <Badge variant="secondary">Current</Badge>}
						</span>{" "}
						<QuestionnaireChoiceDescription className="mt-1 block">
							{description}
						</QuestionnaireChoiceDescription>{" "}
						<span className="mt-3 block border-t border-border pt-3 text-sm text-foreground">
							{consequence}
						</span>
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}
