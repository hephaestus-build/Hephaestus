import {
	Building2Icon,
	CircleHelpIcon,
	CircleOffIcon,
	ClockIcon,
	EyeIcon,
	GraduationCapIcon,
	HandshakeIcon,
	LockIcon,
	ShieldCheckIcon,
	TimerOffIcon,
} from "lucide-react";

import type { AgentBinding, LlmModel, WorkspaceOnboarding } from "@/api/types.gen";
import type { Fact } from "@/components/auth/FactList";

import { type StatusDef, type StatusDefs, statusValues } from "./status-def";

export type DataHandlingTier = LlmModel["dataHandlingTier"];
export type OperatedBy = NonNullable<LlmModel["operatedBy"]>;
export type KeptAfterReply = NonNullable<LlmModel["keptAfterReply"]>;
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

const PROVIDER_UNDER_TERMS: Fact = {
	icon: HandshakeIcon,
	term: "Operated by",
	detail: "A provider under terms your organisation accepted.",
};

/**
 * Strictest first; the order is the server's enum order and the order a developer's ceiling is
 * compared against. Every guarantee row is true for every fact combination that derives to its
 * tier, so this fixed copy never contradicts a stored model. No retention period appears here: the
 * admin note carries it, for admins only.
 */
export const DATA_HANDLING_DEFS: Record<DataHandlingTier, DataHandlingDef> = {
	IN_HOUSE: {
		label: "Stays in-house",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Runs only on systems your organisation operates.",
		facts: [
			{ icon: Building2Icon, term: "Operated by", detail: "Your organisation." },
			{ icon: LockIcon, term: "Where it goes", detail: "Never leaves it." },
			NEVER_TRAINED,
			{
				icon: ClockIcon,
				term: "Kept after the reply",
				detail: "Whatever is kept stays under your organisation's own rules.",
			},
		],
	},
	PROVIDER_NOT_KEPT: {
		label: "Provider, nothing kept",
		icon: ShieldCheckIcon,
		badgeVariant: "secondary",
		description:
			"A provider under terms your organisation accepted processes it and keeps nothing after the reply.",
		facts: [
			PROVIDER_UNDER_TERMS,
			{ icon: TimerOffIcon, term: "Kept after the reply", detail: "Nothing." },
			NEVER_TRAINED,
		],
	},
	PROVIDER_KEPT: {
		label: "Provider, kept for safety checks",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"A provider under terms your organisation accepted keeps it for a limited time for safety checks, which its staff may read if flagged.",
		facts: [
			PROVIDER_UNDER_TERMS,
			{
				icon: ClockIcon,
				term: "Kept after the reply",
				detail: "For a limited time, then deleted.",
			},
			NEVER_TRAINED,
			{
				icon: EyeIcon,
				term: "Who reads it",
				detail: "The provider's staff may read it if a safety check flags it.",
			},
		],
	},
	UNDECLARED: {
		label: "Not declared",
		icon: CircleHelpIcon,
		badgeVariant: "warning",
		description: "An admin has not declared how this model handles data.",
		facts: [],
	},
};

export const DATA_HANDLING_TIERS = statusValues(DATA_HANDLING_DEFS);

/** Client twin of the server's `DataHandlingFacts.tier()`, for the live form preview. */
export function deriveDataHandlingTier(
	operatedBy: OperatedBy | undefined,
	keptAfterReply: KeptAfterReply | undefined,
): DataHandlingTier {
	if (operatedBy === undefined || keptAfterReply === undefined) return "UNDECLARED";
	if (operatedBy === "OWN_ORGANISATION") return "IN_HOUSE";
	return keptAfterReply === "NONE" ? "PROVIDER_NOT_KEPT" : "PROVIDER_KEPT";
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

export const KEPT_AFTER_REPLY_DEFS: StatusDefs<KeptAfterReply> = {
	NONE: {
		label: "Nothing",
		icon: TimerOffIcon,
		badgeVariant: "secondary",
		description:
			"Nothing stays behind once the reply is returned; automated safety checks may still run.",
	},
	FOR_SAFETY_CHECKS: {
		label: "For safety checks",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"Kept for a limited time, then deleted; the provider's staff may read it if flagged.",
	},
};

/** A choice's entry names the loosest tier the developer accepts; `null` is no AI at all. */
export interface MemberAiChoiceDef extends StatusDef {
	consideration: string;
	ceiling: DataHandlingTier | null;
}

/**
 * Card order: the three AI answers strictest first, then the answer that needs nothing set up. A
 * choice is a ceiling, so anything stricter also counts and nothing ever moves a developer to a
 * looser tier.
 */
export const MEMBER_AI_CHOICE_DEFS: Record<MemberAiChoice, MemberAiChoiceDef> = {
	IN_HOUSE_ONLY: {
		label: "Only in-house",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Runs only on systems your organisation operates.",
		consideration:
			"Limited to your organisation’s models and capacity. Its own storage rules still apply.",
		ceiling: "IN_HOUSE",
	},
	NOT_KEPT_ONLY: {
		label: "Allow providers without content storage",
		icon: ShieldCheckIcon,
		badgeVariant: "secondary",
		description:
			"Also allows approved providers that do not store your prompts or replies after responding.",
		consideration:
			"Your work may leave your organisation. Providers may still keep usage metadata; in-house storage rules still apply.",
		ceiling: "PROVIDER_NOT_KEPT",
	},
	ANY_DECLARED: {
		label: "Allow storage for safety checks",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"Also allows approved providers that retain content for safety checks. This permits more options, not necessarily better models.",
		consideration:
			"Prompts and replies may be stored for safety checks, and provider staff may read flagged content.",
		ceiling: "PROVIDER_KEPT",
	},
	NO_AI: {
		label: "No AI",
		icon: CircleOffIcon,
		badgeVariant: "secondary",
		description: "No new practice reviews about you or new requests to Heph in this workspace.",
		consideration:
			"You won’t get new AI feedback or use Heph here. Membership and existing feedback stay unchanged.",
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
	if (choice === null) return live.find((binding) => binding.dataHandlingTier === "UNDECLARED");
	const { ceiling } = MEMBER_AI_CHOICE_DEFS[choice];
	if (ceiling === null) return undefined;
	return live
		.filter((binding) => tierIsWithin(binding.dataHandlingTier, ceiling))
		.sort(
			(a, b) =>
				DATA_HANDLING_TIERS.indexOf(b.dataHandlingTier) -
				DATA_HANDLING_TIERS.indexOf(a.dataHandlingTier),
		)[0];
}
