import {
	Building2Icon,
	CircleHelpIcon,
	CircleOffIcon,
	CloudIcon,
	EyeIcon,
	GraduationCapIcon,
	HandshakeIcon,
	LockIcon,
} from "lucide-react";

import type { AgentBinding, LlmModel, WorkspaceOnboarding } from "@/api/types.gen";
import type { Fact } from "@/components/auth/FactList";

import { type StatusDef, type StatusDefs, statusValues } from "@/components/common/status-def";

export type DataHandlingTier = LlmModel["dataHandlingTier"];
export type OperatedBy = NonNullable<LlmModel["operatedBy"]>;
export type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

/** A tier's registry entry also carries the guarantees a developer may hold the admin to. */
export interface DataHandlingDef extends StatusDef {
	facts: readonly Fact[];
}

const NEVER_TRAINED: Fact = {
	icon: GraduationCapIcon,
	term: "Training",
	detail: "Never used for training.",
};

/**
 * Strictest first. The order is the server's enum order and the order a developer's ceiling is
 * compared against. Every guarantee row is true for every model that derives to its tier, so this
 * fixed copy never contradicts a stored model. No retention period appears here. The admin note
 * carries it, for admins only.
 */
export const DATA_HANDLING_DEFS: Record<DataHandlingTier, DataHandlingDef> = {
	IN_HOUSE: {
		label: "In-house",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Runs only on systems your organisation runs.",
		facts: [
			{ icon: Building2Icon, term: "Operated by", detail: "Your organisation." },
			{ icon: LockIcon, term: "Where it goes", detail: "Never leaves your organisation." },
			NEVER_TRAINED,
		],
	},
	CLOUD: {
		label: "Cloud",
		icon: CloudIcon,
		badgeVariant: "secondary",
		description: "A provider your organisation approved handles it.",
		facts: [
			{
				icon: HandshakeIcon,
				term: "Operated by",
				detail: "A provider under terms your organisation accepted.",
			},
			{
				icon: EyeIcon,
				term: "Kept and read",
				detail: "May be kept briefly for safety checks. Provider staff may read flagged content.",
			},
			NEVER_TRAINED,
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
		description: "A provider under terms your organisation accepted.",
	},
};

/**
 * The five facts every answer card compares, in row order. The same slot sits in the same row on
 * every card, so a reader compares across cards without hunting.
 */
export const CHOICE_FACT_SLOTS = ["feedback", "where", "kept", "reads", "models"] as const;
export type ChoiceFactSlot = (typeof CHOICE_FACT_SLOTS)[number];

/** How a fact reads for this answer: a plus, a caveat, a minus, or a plain fact. */
export type ChoiceTone = "pro" | "caveat" | "con" | "neutral";

export interface ChoiceFact {
	tone: ChoiceTone;
	text: string;
}

/**
 * The colour per tone. The card draws a different shape for each (a check, a triangle, a cross, an
 * "i"), so the tone survives greyscale.
 */
export const CHOICE_TONE_CLASS: Record<ChoiceTone, string> = {
	pro: "text-success",
	caveat: "text-warning",
	con: "text-destructive",
	neutral: "text-muted-foreground",
};

/**
 * A choice's entry names the loosest tier the developer accepts. `null` is no AI at all. The two AI
 * answers carry the tier's own label and icon, so a developer's card and an admin's row wear the
 * same words.
 */
export interface MemberAiChoiceDef extends StatusDef {
	facts: Record<ChoiceFactSlot, ChoiceFact>;
	ceiling: DataHandlingTier | null;
}

/**
 * Card order: in-house, cloud, then the answer that needs nothing set up. A choice is a ceiling, so
 * Cloud also allows in-house models and nothing ever moves a developer to a looser tier. The
 * `description` is the one-line tagline under the title. The facts are trade-offs, never a pitch.
 */
export const MEMBER_AI_CHOICE_DEFS: Record<MemberAiChoice, MemberAiChoiceDef> = {
	IN_HOUSE_ONLY: {
		label: "In-house",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Your organisation’s own AI.",
		facts: {
			feedback: { tone: "pro", text: "Practice feedback and Heph" },
			where: { tone: "pro", text: "Stays inside your organisation" },
			kept: { tone: "neutral", text: "Kept under your organisation's rules" },
			reads: { tone: "pro", text: "Only your organisation can read it" },
			models: { tone: "caveat", text: "Only the models your organisation runs" },
		},
		ceiling: "IN_HOUSE",
	},
	CLOUD: {
		label: "Cloud",
		icon: CloudIcon,
		badgeVariant: "secondary",
		description: "Adds approved cloud providers.",
		facts: {
			feedback: { tone: "pro", text: "Practice feedback and Heph" },
			where: { tone: "caveat", text: "Leaves your organisation for a provider" },
			kept: { tone: "caveat", text: "May be kept briefly for safety checks" },
			reads: { tone: "caveat", text: "Provider staff may read flagged content" },
			models: { tone: "neutral", text: "The models your workspace approved" },
		},
		ceiling: "CLOUD",
	},
	NO_AI: {
		label: "No AI",
		icon: CircleOffIcon,
		badgeVariant: "secondary",
		description: "Hephaestus without AI.",
		facts: {
			feedback: { tone: "con", text: "No practice feedback, no Heph" },
			where: { tone: "pro", text: "Nothing is sent anywhere" },
			kept: { tone: "pro", text: "Nothing is kept" },
			reads: { tone: "pro", text: "No one reads your work" },
			models: { tone: "neutral", text: "No models" },
		},
		ceiling: null,
	},
};

export function memberAiChoiceTitle(choice: MemberAiChoice): string {
	return MEMBER_AI_CHOICE_DEFS[choice].label;
}

type RoutableBinding = Pick<AgentBinding, "dataHandlingTier" | "enabled" | "ready">;

/**
 * Client twin of the server's routing rule, for the admin preview: among the bindings of one
 * purpose, the loosest tier within the developer's ceiling that is on and ready. A developer who
 * has not chosen (`null`) is served only by the undeclared slot, never by a declared one — and only
 * where the choice is optional. Whether it is required is the workspace's setting, not a fact of
 * any binding, so the caller that holds `aiChoiceRequired` asks for the `null` row only when it is
 * false; asked regardless, this previews a model the server would never serve.
 */
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
