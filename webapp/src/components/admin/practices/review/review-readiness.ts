import {
	CircleCheckIcon,
	CircleHelpIcon,
	CircleSlashIcon,
	LoaderIcon,
	TriangleAlertIcon,
} from "lucide-react";
import type { AgentBinding } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";
import {
	DATA_HANDLING_DEFS,
	DATA_HANDLING_TIERS,
	type DataHandlingTier,
} from "@/components/practice-vocabulary/data-handling-defs";

export type ReviewModelState =
	| { status: "loading" }
	| { status: "error" }
	| { status: "ready"; binding?: AgentBinding };

export function reviewModelRunnable(model: ReviewModelState): boolean {
	return model.status === "ready" && model.binding?.ready === true && model.binding.enabled;
}

export interface ReviewReadinessState {
	enabled: boolean;
	model: ReviewModelState;
}

export type ReviewReadinessTone = "ready" | "checking" | "unconfirmed" | "blocked" | "off";

export const REVIEW_READINESS_DEFS: StatusDefs<ReviewReadinessTone> = {
	ready: {
		label: "Reviews can start",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description:
			"Practice reviews are on and a review model is ready. Each developer’s AI choice still decides whether it runs for them. Feedback mode decides separately where observations become feedback.",
	},
	checking: {
		label: "Checking reviews",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "Looking up whether a review model is ready…",
	},
	unconfirmed: {
		label: "Reviews cannot be confirmed",
		icon: CircleHelpIcon,
		badgeVariant: "warning",
		description: "Practice reviews are on, but we could not check whether a review model is ready.",
	},
	blocked: {
		label: "Reviews cannot start",
		icon: TriangleAlertIcon,
		badgeVariant: "warning",
		description: "Practice reviews are on, but no review model is ready, so none can start.",
	},
	off: {
		label: "Reviews are off",
		icon: CircleSlashIcon,
		badgeVariant: "warning",
		description: "Practice reviews are off in this workspace, so nothing below takes effect yet.",
	},
};

export function reviewReadinessTone({ enabled, model }: ReviewReadinessState): ReviewReadinessTone {
	if (!enabled) {
		return "off";
	}
	if (model.status === "loading") {
		return "checking";
	}
	if (model.status === "error") {
		return "unconfirmed";
	}
	if (!reviewModelRunnable(model)) {
		return "blocked";
	}
	return "ready";
}

/**
 * The registry sentence, except that a running review model is named by its declared data handling:
 * which members it serves is decided by that tier against each developer's AI choice, so the banner
 * says which tier is in force rather than only that one is.
 */
export function reviewReadinessDescription(readiness: ReviewReadinessState): string {
	const tone = reviewReadinessTone(readiness);
	if (tone !== "ready" || readiness.model.status !== "ready" || !readiness.model.binding) {
		return REVIEW_READINESS_DEFS[tone].description;
	}
	const tier = readiness.model.binding.dataHandlingTier;
	const model =
		tier === "UNDECLARED"
			? "a review model is ready for members who have not chosen"
			: `a review model declared as ${DATA_HANDLING_DEFS[tier].label} is ready`;
	return `Practice reviews are on and ${model}. Each developer’s AI choice still decides whether it runs for them. Feedback mode decides separately where observations become feedback.`;
}

export function availableReviewBinding(bindings: AgentBinding[]): AgentBinding | undefined {
	const reviews = bindings.filter((binding) => binding.purpose === "PRACTICE_REVIEW");
	const ready = reviews
		.filter((binding) => binding.enabled && binding.ready)
		.sort(
			(a, b) =>
				DATA_HANDLING_TIERS.indexOf(a.dataHandlingTier) -
				DATA_HANDLING_TIERS.indexOf(b.dataHandlingTier),
		);
	return ready[0] ?? reviews[0];
}

/**
 * The tiers of the members a review model must serve: both AI choices, and the members who have not
 * chosen while the workspace lets them leave the choice open.
 */
export function memberTiers(aiChoiceRequired: boolean): DataHandlingTier[] {
	return DATA_HANDLING_TIERS.filter((tier) => !aiChoiceRequired || tier !== "UNDECLARED");
}

/** The member tiers that no practice review binding serves, judged by the server's served tiers. */
export function unservedReviewTiers(
	bindings: readonly AgentBinding[],
	aiChoiceRequired: boolean,
): DataHandlingTier[] {
	const served = new Set(
		bindings
			.filter((binding) => binding.purpose === "PRACTICE_REVIEW")
			.flatMap((binding) => binding.servedTiers),
	);
	return memberTiers(aiChoiceRequired).filter((tier) => !served.has(tier));
}
