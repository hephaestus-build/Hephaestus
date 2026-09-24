import {
	BotIcon,
	CircleCheckIcon,
	CircleHelpIcon,
	CircleXIcon,
	InfoIcon,
	TriangleAlertIcon,
} from "lucide-react";

import { cn } from "cn";

import type { WorkspaceAiModel } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { AI_CONNECTION_PLATFORM_META } from "@/components/icons/ai-connection-platform-logos";
import { AI_MODEL_BRAND_META } from "@/components/icons/ai-model-brand-logos";
import {
	MEMBER_AI_CHOICE_DEFS,
	MEMBER_AI_CHOICE_DIMENSIONS,
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
	IN_HOUSE_ONLY: "bg-background ring-1 ring-inset ring-mentor/25",
	CLOUD: "bg-accent",
	NO_AI: "bg-muted",
};

const CHOICE_ICON_COLORS: Record<MemberAiChoice, string> = {
	IN_HOUSE_ONLY: "text-mentor",
	CLOUD: "text-foreground",
	NO_AI: "text-muted-foreground",
};

const FACT_ICONS = {
	pro: CircleCheckIcon,
	caveat: TriangleAlertIcon,
	con: CircleXIcon,
	neutral: InfoIcon,
};

const FACT_COLORS = {
	pro: "text-success",
	caveat: "text-warning",
	con: "text-destructive",
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
							data-slot="ai-choice-header"
							className={cn(
								"flex flex-col gap-2 rounded-lg p-3 md:min-h-44",
								CHOICE_VISUALS[value],
							)}
						>
							<span className="flex items-center gap-2 text-base font-semibold text-foreground">
								<Icon
									aria-hidden="true"
									className={cn("size-5 shrink-0", CHOICE_ICON_COLORS[value])}
								/>
								{label}{" "}
								{saved === value && (
									<Badge variant="secondary" className="ml-auto">
										Current
									</Badge>
								)}
							</span>{" "}
							<QuestionnaireChoiceDescription className="block">
								{description}
							</QuestionnaireChoiceDescription>{" "}
							{modelsByChoice && value !== "NO_AI" && (
								<span className="mt-auto grid gap-1 rounded-md border border-border bg-background/80 px-2.5 py-2 text-xs text-foreground">
									{model ? (
										<>
											<span className="flex min-w-0 items-center gap-1.5">
												{brand ? (
													<img
														src={brand.src}
														alt=""
														className="size-4 shrink-0 rounded-sm bg-white p-0.5"
													/>
												) : (
													<BotIcon aria-hidden="true" className="size-4 shrink-0" />
												)}
												<span className="min-w-0 truncate font-medium">{model.name}</span>
												{availableModels.length > 1 && (
													<span className="ml-auto shrink-0 text-muted-foreground">
														+{availableModels.length - 1}
													</span>
												)}
											</span>
											<span className="flex min-w-0 items-center gap-1.5 text-muted-foreground">
												{platform ? (
													<img
														src={platform.src}
														alt=""
														className="size-4 shrink-0 rounded-sm bg-white p-0.5"
													/>
												) : (
													<CircleHelpIcon aria-hidden="true" className="size-4 shrink-0" />
												)}
												<span className="min-w-0 truncate">
													{platform ? `via ${platform.label}` : "Service not declared"}
												</span>
											</span>
										</>
									) : (
										<span className="text-muted-foreground">No model ready here</span>
									)}
								</span>
							)}
						</span>
						<span className="mt-3 grid border-t border-border text-sm leading-snug">
							{facts.map(({ tone, text }, index) => {
								const FactIcon = FACT_ICONS[tone];
								return (
									<span
										key={MEMBER_AI_CHOICE_DIMENSIONS[index]}
										className="grid content-start gap-1.5 border-b border-border py-2.5 last:border-b-0 md:min-h-26"
									>
										<span className="text-xs font-medium text-muted-foreground">
											{MEMBER_AI_CHOICE_DIMENSIONS[index]}
										</span>
										<span className="flex items-start gap-2">
											<FactIcon
												aria-hidden="true"
												className={cn("mt-0.5 size-4 shrink-0", FACT_COLORS[tone])}
											/>
											<span>{text} </span>
										</span>
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
