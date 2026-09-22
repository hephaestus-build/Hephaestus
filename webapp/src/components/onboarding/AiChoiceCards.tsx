import { cn } from "cn";

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
 * The four answers as equal cards inside a `QuestionnaireItem` the caller owns. Every card has the
 * same anatomy, top to bottom: an icon chip and the title, a one-line tagline, the trade-offs as
 * icon rows, and a reach meter with a caption; the title is the admin's tier wording. Nothing is pre-selected, no card
 * carries workspace configuration, and the meter is the only ordered element: it shows how far the
 * work may travel, filled from in-house outward, so the strictest and loosest answers read as such
 * without a sentence. No AI has an empty meter; it is the answer that sends nothing anywhere.
 */
export function AiChoiceCards({ choice, onChoice }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 sm:grid-cols-2">
			{AI_CHOICES.map((value) => {
				const {
					icon: Icon,
					label,
					description,
					points,
					reach,
					reachLabel,
					ceiling,
				} = MEMBER_AI_CHOICE_DEFS[value];
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
						<span className="flex items-center gap-3">
							<span
								aria-hidden="true"
								className={cn(
									"inline-flex size-9 shrink-0 items-center justify-center rounded-md [&_svg]:size-5",
									off ? "bg-muted text-muted-foreground" : "bg-mentor/10 text-mentor",
								)}
							>
								<Icon />
							</span>
							<span className="flex min-w-0 flex-col">
								<span className="font-medium">{label}</span>{" "}
								<span className="text-xs text-muted-foreground">{description}</span>
							</span>
						</span>{" "}
						<QuestionnaireChoiceDescription>
							<span className="mt-3 flex flex-col gap-1.5 border-t pt-3 text-xs">
								{points.map(({ icon: PointIcon, text }) => (
									<span key={text} className="flex items-start gap-2">
										<PointIcon className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
										{text}{" "}
									</span>
								))}
							</span>{" "}
							<span className="mt-3 flex items-center gap-2 text-xs">
								<ReachMeter reach={reach} />
								{reachLabel}
							</span>
						</QuestionnaireChoiceDescription>
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}

/**
 * Three segments filled from the left: in-house, then a provider that keeps nothing, then one that
 * keeps content. Fill is the channel, not colour, so the order survives greyscale; the sentence
 * beside it names the ceiling for a screen reader.
 */
function ReachMeter({ reach }: { reach: 0 | 1 | 2 | 3 }) {
	return (
		<span aria-hidden="true" className="inline-flex shrink-0 gap-0.5">
			{[1, 2, 3].map((segment) => (
				<span
					key={segment}
					className={cn(
						"h-1.5 w-4 rounded-full",
						segment <= reach ? "bg-mentor" : "bg-muted-foreground/25",
					)}
				/>
			))}
		</span>
	);
}
