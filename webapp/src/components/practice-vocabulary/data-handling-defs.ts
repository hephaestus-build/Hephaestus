import {
	Building2Icon,
	CircleHelpIcon,
	CircleOffIcon,
	CloudIcon,
	EyeIcon,
	HandshakeIcon,
	HouseIcon,
	LockIcon,
} from "lucide-react";

import type { AgentBinding, LlmModel, WorkspaceOnboarding } from "@/api/types.gen";
import type { Fact } from "@/components/auth/FactList";

import { type StatusDef, type StatusDefs, statusValues } from "@/components/common/status-def";

export type DataHandlingTier = LlmModel["dataHandlingTier"];
export type OperatedBy = NonNullable<LlmModel["operatedBy"]>;
export type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

export interface DataHandlingDef extends StatusDef {
	facts: readonly Fact[];
}

export const DATA_HANDLING_DEFS: Record<DataHandlingTier, DataHandlingDef> = {
	IN_HOUSE: {
		label: "In-house",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Runs on systems your organisation operates.",
		facts: [
			{ icon: Building2Icon, term: "Operated by", detail: "Your organisation." },
			{
				icon: LockIcon,
				term: "Where it goes",
				detail: "Processed on systems your organisation operates.",
			},
		],
	},
	CLOUD: {
		label: "Cloud",
		icon: CloudIcon,
		badgeVariant: "secondary",
		description: "A provider configured by your organisation handles AI requests.",
		facts: [
			{
				icon: HandshakeIcon,
				term: "Operated by",
				detail: "A provider configured by your organisation.",
			},
			{
				icon: EyeIcon,
				term: "Kept and read",
				detail: "Provider retention and access depend on its terms.",
			},
		],
	},
	UNDECLARED: {
		label: "Not declared",
		icon: CircleHelpIcon,
		badgeVariant: "warning",
		description: "An admin has not declared who operates this model.",
		facts: [],
	},
};

export const DATA_HANDLING_TIERS = statusValues(DATA_HANDLING_DEFS);

/** Client twin of the server's `DataHandlingFacts.tier()`, for the live form preview. */
export function deriveDataHandlingTier(operatedBy: OperatedBy | undefined): DataHandlingTier {
	if (operatedBy === undefined) {
		return "UNDECLARED";
	}
	return operatedBy === "OWN_ORGANISATION" ? "IN_HOUSE" : "CLOUD";
}

/** `UNDECLARED` sits outside every ceiling: it serves only members who have not chosen. */
export function tierIsWithin(tier: DataHandlingTier, ceiling: DataHandlingTier): boolean {
	return (
		tier !== "UNDECLARED" &&
		DATA_HANDLING_TIERS.indexOf(tier) <= DATA_HANDLING_TIERS.indexOf(ceiling)
	);
}

export const OPERATED_BY_DEFS: StatusDefs<OperatedBy> = {
	OWN_ORGANISATION: {
		label: "Your organisation",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Systems your organisation runs.",
	},
	PROVIDER: {
		label: "A provider",
		icon: HandshakeIcon,
		badgeVariant: "secondary",
		description: "A provider configured by your organisation.",
	},
};

export interface MemberAiChoiceDef extends StatusDef {
	facts: readonly [MemberAiChoiceFact, MemberAiChoiceFact, MemberAiChoiceFact, MemberAiChoiceFact];
	ceiling: DataHandlingTier | null;
}

export interface MemberAiChoiceFact {
	tone: "pro" | "caveat" | "con" | "neutral";
	text: string;
}

export const MEMBER_AI_CHOICE_DIMENSIONS = [
	"AI help",
	"Models",
	"Capacity",
	"New requests",
] as const;

export const MEMBER_AI_CHOICE_DEFS: Record<MemberAiChoice, MemberAiChoiceDef> = {
	IN_HOUSE_ONLY: {
		label: "In-house",
		icon: HouseIcon,
		badgeVariant: "secondary",
		description: "Use models declared in-house.",
		facts: [
			{ tone: "pro", text: "Feedback and Heph, when ready." },
			{ tone: "caveat", text: "Only in-house models." },
			{ tone: "caveat", text: "In-house capacity may limit model size or speed." },
			{ tone: "pro", text: "Only in-house models receive them." },
		],
		ceiling: "IN_HOUSE",
	},
	CLOUD: {
		label: "Cloud",
		icon: CloudIcon,
		badgeVariant: "secondary",
		description: "Also use provider-operated models.",
		facts: [
			{ tone: "pro", text: "Feedback and Heph, when ready." },
			{ tone: "pro", text: "In-house and provider models." },
			{ tone: "neutral", text: "May offer stronger models or more capacity." },
			{ tone: "caveat", text: "A configured provider may receive them." },
		],
		ceiling: "CLOUD",
	},
	NO_AI: {
		label: "No AI",
		icon: CircleOffIcon,
		badgeVariant: "secondary",
		description: "No new AI for your work. Sync and stored work stay.",
		facts: [
			{ tone: "con", text: "No new feedback or Heph replies." },
			{ tone: "con", text: "No model is used." },
			{ tone: "neutral", text: "No model capacity needed." },
			{ tone: "pro", text: "None sent for your work." },
		],
		ceiling: null,
	},
};

export function memberAiChoiceTitle(choice: MemberAiChoice): string {
	return MEMBER_AI_CHOICE_DEFS[choice].label;
}

type RoutableBinding = Pick<AgentBinding, "dataHandlingTier" | "enabled" | "ready">;

/** A null choice can preview only the undeclared slot; the caller checks if answering is required. */
export function bindingFor<TBinding extends RoutableBinding>(
	choice: MemberAiChoice | null,
	bindings: readonly TBinding[],
): TBinding | undefined {
	const live = bindings.filter((binding) => binding.enabled && binding.ready);
	if (choice === null) {
		return live.find((binding) => binding.dataHandlingTier === "UNDECLARED");
	}
	const { ceiling } = MEMBER_AI_CHOICE_DEFS[choice];
	if (ceiling === null) {
		return undefined;
	}
	return live
		.filter((binding) => tierIsWithin(binding.dataHandlingTier, ceiling))
		.sort(
			(a, b) =>
				DATA_HANDLING_TIERS.indexOf(b.dataHandlingTier) -
				DATA_HANDLING_TIERS.indexOf(a.dataHandlingTier),
		)[0];
}
