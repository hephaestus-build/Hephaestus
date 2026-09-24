import { CircleCheckIcon, InfoIcon } from "lucide-react";

import { cn } from "cn";

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

const CHOICE_VISUALS: Record<MemberAiChoice, string> = {
	IN_HOUSE_ONLY: "bg-mentor/10 text-mentor",
	CLOUD: "bg-accent text-accent-foreground",
	NO_AI: "bg-muted text-muted-foreground",
};

export interface AiChoiceCardsProps {
	choice: MemberAiChoice | undefined;
	saved?: MemberAiChoice;
	onChoice: (choice: MemberAiChoice) => void;
}

export function AiChoiceCards({ choice, saved, onChoice }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 md:grid-cols-3">
			{AI_CHOICES.map((value) => {
				const { label, description, benefit, tradeoff, icon: Icon } = MEMBER_AI_CHOICE_DEFS[value];
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
						<span
							aria-hidden="true"
							className={cn(
								"relative flex h-20 items-center justify-center rounded-lg",
								CHOICE_VISUALS[value],
							)}
						>
							<Icon className="size-9" strokeWidth={1.75} />
						</span>
						<span className="mt-3 flex items-center justify-between gap-2 text-lg font-semibold text-foreground">
							{label} {saved === value && <Badge variant="secondary">Current</Badge>}
						</span>{" "}
						<QuestionnaireChoiceDescription className="block md:min-h-12">
							{description}
						</QuestionnaireChoiceDescription>{" "}
						<span className="mt-4 grid gap-3 border-t border-border pt-3 text-sm leading-snug">
							<span className="flex items-start gap-2.5">
								<CircleCheckIcon
									aria-hidden="true"
									className="mt-0.5 size-4 shrink-0 text-mentor"
								/>
								<span>
									<strong className="font-medium text-foreground">Benefit.</strong> {benefit}
								</span>
							</span>{" "}
							<span className="flex items-start gap-2.5">
								<InfoIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
								<span>
									<strong className="font-medium text-foreground">Trade-off.</strong> {tradeoff}
								</span>
							</span>
						</span>
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}
