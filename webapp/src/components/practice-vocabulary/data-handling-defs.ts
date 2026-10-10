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

import type { LlmModel, WorkspaceOnboarding } from "@/api/types.gen";
import type { Fact } from "@/components/auth/FactList";

import { type StatusDef, type StatusDefs, statusValues } from "@/components/common/status-def";
import { andList } from "@/lib/text";

export type DataHandlingTier = LlmModel["dataHandlingTier"];
export type OperatedBy = NonNullable<LlmModel["operatedBy"]>;
export type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

export interface DataHandlingDef extends StatusDef {
	facts: readonly Fact[];
}

export const DATA_HANDLING_DEFS: Record<DataHandlingTier, DataHandlingDef> = {
	IN_HOUSE: {
		label: "In-house",
		icon: HouseIcon,
		badgeVariant: "secondary",
		description: "Runs on systems your organization operates.",
		facts: [
			{ icon: Building2Icon, term: "Operated by", detail: "Your organization." },
			{
				icon: LockIcon,
				term: "Where it goes",
				detail: "Processed on systems your organization operates.",
			},
		],
	},
	CLOUD: {
		label: "Cloud",
		icon: CloudIcon,
		badgeVariant: "secondary",
		description: "A provider configured by your organization handles AI requests.",
		facts: [
			{
				icon: HandshakeIcon,
				term: "Operated by",
				detail: "A provider configured by your organization.",
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

/** The members an `UNDECLARED` model serves. The tier's own label names the model, not them. */
export const UNCHOSEN_MEMBERS = "members who have not chosen";

/** "In-house and Cloud members and members who have not chosen", in tier order. */
export function tierMembersPhrase(tiers: readonly DataHandlingTier[]): string {
	const ordered = DATA_HANDLING_TIERS.filter((tier) => tiers.includes(tier));
	const named = ordered
		.filter((tier) => tier !== "UNDECLARED")
		.map((tier) => DATA_HANDLING_DEFS[tier].label);
	const groups = named.length > 0 ? [`${andList.format(named)} members`] : [];
	if (ordered.includes("UNDECLARED")) {
		groups.push(UNCHOSEN_MEMBERS);
	}
	return andList.format(groups);
}

/** The members a model misses, as one phrase: "Not set for In-house members". */
export function notSetForPhrase(tiers: readonly DataHandlingTier[]): string {
	return `Not set for ${tierMembersPhrase(tiers)}`;
}

/** Client twin of the server's `DataHandlingFacts.tier()`, for the live form preview. */
export function deriveDataHandlingTier(operatedBy: OperatedBy | undefined): DataHandlingTier {
	if (operatedBy === undefined) {
		return "UNDECLARED";
	}
	return operatedBy === "OWN_ORGANISATION" ? "IN_HOUSE" : "CLOUD";
}

export const OPERATED_BY_DEFS: StatusDefs<OperatedBy> = {
	OWN_ORGANISATION: {
		label: "Your organization",
		icon: Building2Icon,
		badgeVariant: "secondary",
		description: "Systems your organization runs.",
	},
	PROVIDER: {
		label: "A provider",
		icon: HandshakeIcon,
		badgeVariant: "secondary",
		description: "A provider configured by your organization.",
	},
};

/** The rows every answer is compared on, in the order the cards show them. */
export const MEMBER_AI_CHOICE_DIMENSIONS = ["AI help", "Models", "Speed", "Sent to"] as const;
export type MemberAiChoiceDimension = (typeof MEMBER_AI_CHOICE_DIMENSIONS)[number];

export interface MemberAiChoiceFact {
	tone: "pro" | "caveat" | "con" | "none";
	text: string;
	/** The words for one workspace that has no Heph model ready, where `text` would promise Heph. */
	withoutHeph?: string;
}

export interface MemberAiChoiceDef extends StatusDef {
	facts: Record<MemberAiChoiceDimension, MemberAiChoiceFact>;
	ceiling: DataHandlingTier | null;
}

export const MEMBER_AI_CHOICE_DEFS: Record<MemberAiChoice, MemberAiChoiceDef> = {
	IN_HOUSE_ONLY: {
		label: "In-house",
		icon: HouseIcon,
		badgeVariant: "secondary",
		description: "Models your organization runs",
		facts: {
			"AI help": {
				tone: "pro",
				text: "Feedback and Heph",
				withoutHeph: "Practice feedback where set up",
			},
			Models: { tone: "caveat", text: "Usually smaller models" },
			Speed: { tone: "caveat", text: "Limited capacity, can be slower" },
			"Sent to": { tone: "pro", text: "Only your organization" },
		},
		ceiling: "IN_HOUSE",
	},
	CLOUD: {
		label: "Cloud",
		icon: CloudIcon,
		badgeVariant: "secondary",
		description: "Adds models from cloud providers",
		facts: {
			"AI help": {
				tone: "pro",
				text: "Feedback and Heph",
				withoutHeph: "Practice feedback where set up",
			},
			Models: { tone: "pro", text: "Strongest models on offer" },
			Speed: { tone: "pro", text: "More capacity, usually faster" },
			"Sent to": { tone: "caveat", text: "Also a cloud provider" },
		},
		ceiling: "CLOUD",
	},
	NO_AI: {
		label: "No AI",
		icon: CircleOffIcon,
		badgeVariant: "secondary",
		description: "Hephaestus without AI",
		facts: {
			"AI help": { tone: "con", text: "No feedback or Heph", withoutHeph: "No new AI requests" },
			Models: { tone: "none", text: "None" },
			Speed: { tone: "none", text: "Not applicable" },
			"Sent to": { tone: "pro", text: "Nowhere" },
		},
		ceiling: null,
	},
};

export function memberAiChoiceTitle(choice: MemberAiChoice): string {
	return MEMBER_AI_CHOICE_DEFS[choice].label;
}
