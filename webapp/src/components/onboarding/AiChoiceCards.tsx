import {
	CircleCheckIcon,
	CircleMinusIcon,
	CircleOffIcon,
	CircleXIcon,
	type LucideIcon,
	TriangleAlertIcon,
} from "lucide-react";

import { cn } from "cn";

import type { WorkspaceAiModel } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { AI_CONNECTION_PLATFORM_META } from "@/components/icons/ai-connection-platform-logos";
import { AI_MODEL_BRAND_META } from "@/components/icons/ai-model-brand-logos";
import { AiMark } from "@/components/icons/AiMark";
import {
	MEMBER_AI_CHOICE_DEFS,
	MEMBER_AI_CHOICE_DIMENSIONS,
	type MemberAiChoice,
	type MemberAiChoiceFact,
} from "@/components/practice-vocabulary/data-handling-defs";
import { Badge } from "@/components/ui/badge";
import {
	QuestionnaireChoice,
	QuestionnaireChoiceDescription,
	QuestionnaireChoices,
} from "@/components/ui/questionnaire";

export const AI_CHOICES = statusValues(MEMBER_AI_CHOICE_DEFS);

const TONES: Record<MemberAiChoiceFact["tone"], { icon: LucideIcon; className: string }> = {
	pro: { icon: CircleCheckIcon, className: "text-success" },
	caveat: { icon: TriangleAlertIcon, className: "text-warning" },
	con: { icon: CircleXIcon, className: "text-destructive" },
	none: { icon: CircleMinusIcon, className: "text-muted-foreground" },
};

export interface AiChoiceCardsProps {
	choice: MemberAiChoice | undefined;
	saved?: MemberAiChoice;
	onChoice: (choice: MemberAiChoice) => void;
	/** The models each answer reaches in one workspace. Account-wide settings have none to show. */
	workspace?: {
		name: string;
		models: Partial<Record<MemberAiChoice, readonly WorkspaceAiModel[]>>;
	};
}

/**
 * The three answers as equal cards whose rows line up across cards (a CSS subgrid from `md`), so a
 * reader compares one dimension by scanning across. The label is `display: contents` there, which
 * makes each row a grid item of the card; below `md` the cards stack and the label stays a column.
 */
export function AiChoiceCards({ choice, saved, onChoice, workspace }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 md:grid-cols-3">
			{AI_CHOICES.map((value) => {
				const { label, description, facts, icon: Icon, ceiling } = MEMBER_AI_CHOICE_DEFS[value];
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
						className={cn(
							"*:data-[slot=questionnaire-choice-indicator]:absolute *:data-[slot=questionnaire-choice-indicator]:top-4 *:data-[slot=questionnaire-choice-indicator]:right-4",
							"md:grid md:grid-rows-subgrid md:*:data-[slot=questionnaire-choice-label]:contents",
							workspace ? "md:row-span-6" : "md:row-span-5",
						)}
					>
						<span
							aria-hidden="true"
							className="pointer-events-none absolute -inset-px rounded-lg opacity-0 ring-2 ring-primary group-data-checked/questionnaire-choice:opacity-100 motion-safe:transition-opacity"
						/>
						<span data-slot="ai-choice-header" className="flex items-start gap-3 p-1 pe-7">
							<span
								className={cn(
									"inline-flex size-10 shrink-0 items-center justify-center rounded-lg",
									ceiling === null
										? "bg-muted-foreground/10 text-muted-foreground"
										: "bg-mentor/10 text-mentor",
								)}
							>
								<Icon aria-hidden="true" className="size-5" />
							</span>
							<span className="grid min-w-0 gap-0.5">
								<span className="flex flex-wrap items-center gap-x-2 text-base font-semibold text-foreground">
									{label} {saved === value && <Badge variant="secondary">Current</Badge>}
								</span>{" "}
								<QuestionnaireChoiceDescription>{description}</QuestionnaireChoiceDescription>
							</span>
						</span>{" "}
						{workspace && (
							<ChoiceModel
								choice={value}
								workspaceName={workspace.name}
								models={workspace.models[value] ?? []}
							/>
						)}
						{MEMBER_AI_CHOICE_DIMENSIONS.map((dimension) => {
							const { tone, text } = facts[dimension];
							const { icon: ToneIcon, className } = TONES[tone];
							return (
								<span
									key={dimension}
									className="grid content-start gap-1 border-t border-border px-1 pt-2.5 pb-1"
								>
									<span className="text-xs text-muted-foreground">{dimension}</span>
									<span
										className={cn(
											"flex items-start gap-2 font-medium",
											tone === "none" ? "text-muted-foreground" : "text-foreground",
										)}
									>
										<ToneIcon
											aria-hidden="true"
											className={cn("mt-px size-4 shrink-0", className)}
										/>
										{text}
									</span>
								</span>
							);
						})}
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}

/** The model a new request for this answer would reach first, with the service that receives it. */
function ChoiceModel({
	choice,
	workspaceName,
	models,
}: {
	choice: MemberAiChoice;
	workspaceName: string;
	models: readonly WorkspaceAiModel[];
}) {
	const model =
		choice === "CLOUD"
			? (models.find((item) => item.dataHandlingTier === "CLOUD") ?? models[0])
			: models[0];
	if (choice === "NO_AI" || model === undefined) {
		return (
			<span className="flex items-center gap-3 rounded-lg border border-dashed border-border px-2.5 py-2 text-muted-foreground">
				<span className="inline-flex size-11 shrink-0 items-center justify-center">
					{choice === "NO_AI" ? (
						<CircleOffIcon aria-hidden="true" className="size-5" />
					) : (
						<TriangleAlertIcon aria-hidden="true" className="size-5 text-warning" />
					)}
				</span>
				{choice === "NO_AI" ? "No model runs" : `Not set up in ${workspaceName} yet`}
			</span>
		);
	}
	// The mark already shows the maker, so the words name the service when one is declared.
	let detail = "Maker and service not declared";
	if (model.connectionPlatform) {
		detail = `via ${AI_CONNECTION_PLATFORM_META[model.connectionPlatform].label}`;
	} else if (model.brand) {
		detail = AI_MODEL_BRAND_META[model.brand].label;
	}
	const others = models.length - 1;
	return (
		<span
			data-slot="ai-choice-model"
			className="flex items-center gap-3 rounded-lg border border-border bg-background px-2.5 py-2"
		>
			<AiMark brand={model.brand} platform={model.connectionPlatform} size="lg" />
			<span className="grid min-w-0 flex-1">
				<span className="truncate font-semibold text-foreground">{model.name}</span>
				<span className="truncate text-xs text-muted-foreground">{detail}</span>
			</span>
			{others > 0 && (
				<Badge variant="secondary">
					+{others}
					<span className="sr-only"> more models</span>
				</Badge>
			)}{" "}
		</span>
	);
}
