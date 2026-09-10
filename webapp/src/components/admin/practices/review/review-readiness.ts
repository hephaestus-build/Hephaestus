import {
	CircleCheckIcon,
	CircleHelpIcon,
	CircleSlashIcon,
	LoaderIcon,
	TriangleAlertIcon,
} from "lucide-react";
import type { AgentBinding } from "@/api/types.gen";
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
			"Practice reviews are on and a review model is ready. Eligible work can be reviewed; each developer’s AI choice still applies.",
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

/** Any configured processing location can make workspace reviews runnable; individual choices still apply. */
export function availableReviewBinding(bindings: AgentBinding[]): AgentBinding | undefined {
	const reviews = bindings.filter((binding) => binding.purpose === "PRACTICE_REVIEW");
	return reviews.find((binding) => binding.enabled && binding.ready) ?? reviews[0];
}
