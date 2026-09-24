import { CircleCheckIcon, CircleXIcon, InfoIcon, TriangleAlertIcon } from "lucide-react";

import { cn } from "cn";

import type { WorkspaceAiModel } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { AI_CONNECTION_PLATFORM_META } from "@/components/icons/ai-connection-platform-logos";
import { AI_MODEL_BRAND_META } from "@/components/icons/ai-model-brand-logos";
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

const FACT_ICONS = {
	pro: CircleCheckIcon,
	caveat: TriangleAlertIcon,
	con: CircleXIcon,
	neutral: InfoIcon,
};

const FACT_COLORS = {
	pro: "text-mentor",
	caveat: "text-warning",
	con: "text-muted-foreground",
	neutral: "text-muted-foreground",
};

export interface AiChoiceCardsProps {
	choice: MemberAiChoice | undefined;
	saved?: MemberAiChoice;
	onChoice: (choice: MemberAiChoice) => void;
	modelsByChoice?: Partial<Record<MemberAiChoice, readonly WorkspaceAiModel[]>>;
}

export function AiChoiceCards({ choice, saved, onChoice, modelsByChoice }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 md:grid-cols-3">
			{AI_CHOICES.map((value) => {
				const { label, description, facts, icon: Icon } = MEMBER_AI_CHOICE_DEFS[value];
				const availableModels = modelsByChoice?.[value] ?? [];
				const model =
					value === "CLOUD"
						? (availableModels.find((item) => item.dataHandlingTier === "CLOUD") ??
							availableModels[0])
						: availableModels[0];
				const brand = model?.brand ? AI_MODEL_BRAND_META[model.brand] : undefined;
				const platform = model?.connectionPlatform
					? AI_CONNECTION_PLATFORM_META[model.connectionPlatform]
					: undefined;
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
								"relative flex h-24 items-center justify-center overflow-hidden rounded-lg",
								CHOICE_VISUALS[value],
							)}
						>
							<span className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-background/40 to-transparent" />
							<Icon className="size-11" strokeWidth={1.5} />
							{brand && (
								<img
									src={brand.src}
									alt=""
									className="absolute right-4 bottom-3 size-7 rounded-md border border-border bg-white p-1 shadow-sm"
								/>
							)}
						</span>
						<span className="mt-3 flex items-center justify-between gap-2 text-lg font-semibold text-foreground">
							{label} {saved === value && <Badge variant="secondary">Current</Badge>}
						</span>{" "}
						<QuestionnaireChoiceDescription className="block min-h-10">
							{description}
						</QuestionnaireChoiceDescription>{" "}
						{model && (
							<span className="mt-3 flex min-w-0 items-center gap-2 rounded-md border border-border bg-background/70 px-2.5 py-2 text-xs">
								{platform && (
									<img
										src={platform.src}
										alt=""
										className="size-4 shrink-0 rounded-sm bg-white p-0.5"
									/>
								)}
								<span className="min-w-0 truncate font-medium text-foreground">{model.name}</span>
								{platform && (
									<span className="ml-auto shrink-0 text-muted-foreground">
										via {platform.label}
									</span>
								)}
							</span>
						)}
						<span className="mt-4 grid gap-2.5 border-t border-border pt-3 text-sm leading-snug">
							{facts.map(({ tone, text }) => {
								const FactIcon = FACT_ICONS[tone];
								return (
									<span key={text} className="flex items-start gap-2.5">
										<FactIcon
											aria-hidden="true"
											className={cn("mt-0.5 size-4 shrink-0", FACT_COLORS[tone])}
										/>
										<span>{text} </span>
									</span>
								);
							})}
						</span>
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}
