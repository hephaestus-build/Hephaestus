import { cn } from "cn";

import { statusValues } from "@/components/common/status-def";
import {
	CHOICE_FACT_SLOTS,
	CHOICE_TONE_DEFS,
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
	/** The checked card. `undefined` checks nothing, so a first answer is never pre-selected. */
	choice: MemberAiChoice | undefined;
	/** The answer already saved, if any. Its card wears a Current badge and nothing else changes. */
	saved?: MemberAiChoice;
	onChoice: (choice: MemberAiChoice) => void;
}

/**
 * The three answers side by side, inside a `QuestionnaireItem` the caller owns. Every card has the
 * same anatomy: a hero band with the answer's icon, the title and a one-line tagline, then the same
 * five facts in the same rows, each marked as a plus, a caveat or a minus with an icon and a colour.
 * Fixed hero and header heights keep the rows level across cards, so the reader compares by
 * scanning across. Nothing is pre-selected and no card carries workspace configuration.
 */
export function AiChoiceCards({ choice, saved, onChoice }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 md:grid-cols-3">
			{AI_CHOICES.map((value) => {
				const { icon: Icon, label, description, facts, ceiling } = MEMBER_AI_CHOICE_DEFS[value];
				const off = ceiling === null;
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
						<span
							aria-hidden="true"
							className={cn(
								"flex h-20 items-center justify-center rounded-md",
								off ? "bg-muted" : "bg-mentor/10",
							)}
						>
							<span
								className={cn(
									"inline-flex size-12 items-center justify-center rounded-full bg-background shadow-xs [&_svg]:size-6",
									off ? "text-muted-foreground" : "text-mentor",
								)}
							>
								<Icon />
							</span>
						</span>{" "}
						<span className="mt-3 flex min-h-14 flex-col gap-0.5">
							<span className="flex items-center gap-2 text-base font-medium">
								{label} {saved === value && <Badge variant="secondary">Current</Badge>}
							</span>{" "}
							<span className="text-xs text-muted-foreground">{description}</span>
						</span>{" "}
						<QuestionnaireChoiceDescription>
							<span className="mt-3 flex flex-col gap-2 border-t pt-3 text-xs">
								{CHOICE_FACT_SLOTS.map((slot) => {
									const { tone, text } = facts[slot];
									const { icon: ToneIcon, className } = CHOICE_TONE_DEFS[tone];
									return (
										<span key={slot} className="flex items-start gap-2">
											<ToneIcon
												className={cn("mt-px size-3.5 shrink-0", className)}
												aria-hidden="true"
											/>
											{text}{" "}
										</span>
									);
								})}
							</span>
						</QuestionnaireChoiceDescription>
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}
