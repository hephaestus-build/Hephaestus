import { ListChecksIcon } from "lucide-react";
import { type ComponentType, createElement } from "react";
import { UNKNOWN_STATUS_DEF, definitionFor, statusValues } from "@/components/common/status-def";

import type { ModelAssignment } from "@/api/types.gen";
import { HephaestusMark } from "@/components/brand/HephaestusMark";

export type ModelPurpose = ModelAssignment["purpose"];

/** A kind's glyph. `size` in pixels, because Hephaestus's mark sizes itself from it, not from a class. */
type ModelKindIcon = ComponentType<{ size: number; className?: string }>;

function ConversationKindIcon({ size, className }: { size: number; className?: string }) {
	return createElement(HephaestusMark, { size, className });
}

interface ModelPurposeDef {
	title: string;
	/** The name in a list that already says it is about models, such as a *Models* column. */
	short: string;
	noun: string;
	withArticle: string;
	description: string;
	icon: ModelKindIcon;
	/** The glyph's colour, as a complete class string. Known kinds stay neutral. */
	tone: string;
}

export const UNKNOWN_MODEL_PURPOSE_DEF: ModelPurposeDef = {
	title: "Unknown model purpose",
	short: "Unknown",
	noun: "model for an unknown purpose",
	withArticle: "an unknown model purpose",
	description: UNKNOWN_STATUS_DEF.description,
	icon: UNKNOWN_STATUS_DEF.icon,
	tone: "text-muted-foreground",
};

// Agent purposes are model kinds, so their registry has no status tone.
export const MODEL_PURPOSE_DEFS = {
	PRACTICE_REVIEW: {
		title: "Practice reviews",
		short: "Review",
		noun: "review model",
		withArticle: "the review model",
		description: "Checks connected project work and conversations against your practices.",
		icon: ListChecksIcon,
		tone: "text-foreground",
	},
	CONVERSATION: {
		title: "Ask Hephaestus",
		short: "Conversation",
		noun: "conversation model",
		withArticle: "the conversation model",
		description:
			"Answers members in their conversations. Every member can ask once a conversation model is ready.",
		icon: ConversationKindIcon,
		tone: "text-foreground",
	},
} satisfies Record<ModelPurpose, ModelPurposeDef>;

export const MODEL_PURPOSES = statusValues(MODEL_PURPOSE_DEFS);

export function isModelPurpose(value: string): value is ModelPurpose {
	return Object.hasOwn(MODEL_PURPOSE_DEFS, value);
}

export function assignModelLabel(purpose: ModelPurpose): string {
	return `Assign ${definitionFor(MODEL_PURPOSE_DEFS, purpose, UNKNOWN_MODEL_PURPOSE_DEF).withArticle}`;
}
