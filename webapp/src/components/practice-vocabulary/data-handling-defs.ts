import {
	Building2Icon,
	CircleHelpIcon,
	CircleOffIcon,
	CloudIcon,
	EyeIcon,
	HandshakeIcon,
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
	benefit: string;
	tradeoff: string;
	ceiling: DataHandlingTier | null;
}

export const MEMBER_AI_CHOICE_DEFS: Record<MemberAiChoice, MemberAiChoiceDef> = {
	IN_HOUSE_ONLY: {
		label: "In-house",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Keep AI processing under your organisation’s operation.",
		benefit: "Only models declared as organisation-operated may handle new AI requests.",
		tradeoff: "If no in-house model is ready here, AI features wait.",
		ceiling: "IN_HOUSE",
	},
	CLOUD: {
		label: "Cloud",
		icon: CloudIcon,
		badgeVariant: "secondary",
		description: "Use in-house or provider-operated AI.",
		benefit: "Both in-house and provider-operated models are allowed.",
		tradeoff: "A configured provider may receive work sent for AI.",
		ceiling: "CLOUD",
	},
	NO_AI: {
		label: "No AI",
		icon: CircleOffIcon,
		badgeVariant: "secondary",
		description: "Use Hephaestus without new AI reviews or Heph replies.",
		benefit: "No new AI requests for practice reviews or Heph.",
		tradeoff: "No new AI feedback or Heph replies; sync and stored work continue.",
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
