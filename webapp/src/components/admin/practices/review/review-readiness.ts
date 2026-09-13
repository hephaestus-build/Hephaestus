import {
	CircleCheckIcon,
	CircleHelpIcon,
	CircleSlashIcon,
	LoaderIcon,
	TriangleAlertIcon,
} from "lucide-react";
import type { AgentBinding } from "@/api/types.gen";
import {
	DATA_HANDLING_DEFS,
	DATA_HANDLING_TIERS,
} from "@/components/practice-vocabulary/data-handling-defs";
import type { StatusDefs } from "@/components/practice-vocabulary/status-def";

export type ReviewModelState =
	| { status: "loading" }
	| { status: "error" }
	| { status: "ready"; binding?: AgentBinding };

export function reviewModelRunnable(model: ReviewModelState): boolean {
	return model.status === "ready" && model.binding?.ready === true && model.binding.enabled;
}

export interface ReviewRunningState {
	enabled: boolean;
	model: ReviewModelState;
}

export type ReviewRunningTone = "running" | "checking" | "unconfirmed" | "blocked" | "off";

export const REVIEW_RUNNING_DEFS: StatusDefs<ReviewRunningTone> = {
	running: {
		label: "Reviews are running",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description:
			"Practice reviews are on and a review model is ready. Each developer's AI choice still decides whether it runs for them.",
	},
	checking: {
		label: "Checking reviews",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "Looking up whether a review model is ready…",
	},
	unconfirmed: {
		label: "Reviews can't be confirmed",
		icon: CircleHelpIcon,
		badgeVariant: "warning",
		description:
			"Practice reviews are on, but whether a review model is ready couldn't be checked just now.",
	},
	blocked: {
		label: "Reviews can't start",
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

export function reviewRunningTone({ enabled, model }: ReviewRunningState): ReviewRunningTone {
	if (!enabled) return "off";
	if (model.status === "loading") return "checking";
	if (model.status === "error") return "unconfirmed";
	if (!reviewModelRunnable(model)) return "blocked";
	return "running";
}

/**
 * The registry sentence, except that a running review model is named by its declared data handling:
 * which members it serves is decided by that tier against each developer's AI choice, so the banner
 * says which tier is in force rather than only that one is.
 */
export function reviewRunningDescription(running: ReviewRunningState): string {
	const tone = reviewRunningTone(running);
	if (tone !== "running" || running.model.status !== "ready" || !running.model.binding) {
		return REVIEW_RUNNING_DEFS[tone].description;
	}
	const tier = running.model.binding.dataHandlingTier;
	const subject =
		tier === "UNDECLARED"
			? "a review model is ready for members who have not chosen"
			: `a review model declared as ${DATA_HANDLING_DEFS[tier].label} is ready`;
	return `Practice reviews are on and ${subject}. Each developer's AI choice still decides whether it runs for them.`;
}

/**
 * The strictest ready review binding: it is the one within the most developers' AI choices, so it is
 * the one the banner names. Falls back to any review binding so a blocked state still has a subject.
 */
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
