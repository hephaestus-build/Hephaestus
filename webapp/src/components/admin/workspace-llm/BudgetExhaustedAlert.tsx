import { Link } from "@tanstack/react-router";
import { TriangleAlert } from "lucide-react";

import type { WorkspaceLlmUsageReport } from "@/api";
import { budgetResetDayLabel, currentMonthUtc } from "@/components/admin/usage/usage-utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";

type BudgetVerdict = WorkspaceLlmUsageReport["ownProviderBudgetVerdict"];

/** `own` is the cap the workspace sets on its own provider; `shared` is the host's budget. */
type CapScope = "shared" | "own";
type PauseReason = "CAP_REACHED" | "UNVERIFIABLE";

/** The two caps pause independently and are never merged into one banner. */
const PAUSE_COPY: Record<
	CapScope,
	Record<PauseReason, { title: string; body: (parts: PauseCopyParts) => string }>
> = {
	own: {
		CAP_REACHED: {
			title: "Your provider cap is reached",
			body: ({ resetDay }) =>
				`Paused until ${resetDay} (UTC), or until you raise or remove the cap.`,
		},
		UNVERIFIABLE: {
			title: "Your provider cap cannot be enforced",
			body: () =>
				"Your provider is paused because some usage has no confirmed cost. Rates may be missing or measurements unverified. Adding rates repairs only verified usage; removing the cap also resumes calls.",
		},
	},
	shared: {
		CAP_REACHED: {
			title: "Shared-model budget reached",
			body: ({ resetDay }) =>
				`Paused until ${resetDay} (UTC), or until an instance admin raises the budget. Practice reviews and conversations can keep running on your own models.`,
		},
		UNVERIFIABLE: {
			title: "Shared-model spend cannot be verified",
			body: () =>
				"Shared models are paused because some usage has no confirmed cost. Rates may be missing or measurements unverified. Only an instance admin can add shared-model rates, and only verified usage can be repriced.",
		},
	},
};

interface PauseCopyParts {
	resetDay: string;
}

interface BudgetExhaustedAlertProps {
	scope: CapScope;
	verdict: BudgetVerdict;
	/** ISO `yyyy-MM`; defaults to the current UTC month, the only month a live pause can be in. */
	month?: string;
	/** Selects the action offered, never a word of the sentence. */
	context: "usage" | "models";
	workspaceSlug: string;
	onEditOwnProviderCap?: () => void;
}

export function BudgetExhaustedAlert({
	scope,
	verdict,
	month = currentMonthUtc(),
	context,
	workspaceSlug,
	onEditOwnProviderCap,
}: BudgetExhaustedAlertProps) {
	const unverifiable = verdict === "UNVERIFIABLE";
	const copy = PAUSE_COPY[scope][unverifiable ? "UNVERIFIABLE" : "CAP_REACHED"];
	return (
		<Alert variant={scope === "own" ? "destructive" : "warning"} role="alert">
			<TriangleAlert aria-hidden />
			<AlertTitle>{copy.title}</AlertTitle>
			<AlertDescription>
				<p>
					{copy.body({
						resetDay: budgetResetDayLabel(month),
					})}
				</p>
				<PauseAction
					scope={scope}
					unverifiable={unverifiable}
					context={context}
					workspaceSlug={workspaceSlug}
					onEditOwnProviderCap={onEditOwnProviderCap}
				/>
			</AlertDescription>
		</Alert>
	);
}

interface PauseActionProps {
	scope: CapScope;
	unverifiable: boolean;
	context: "usage" | "models";
	workspaceSlug: string;
	onEditOwnProviderCap?: () => void;
}

/** Caps are edited on AI usage and prices on AI models, so each page links out for the other. */
function PauseAction({
	scope,
	unverifiable,
	context,
	workspaceSlug,
	onEditOwnProviderCap,
}: PauseActionProps) {
	if (scope === "own") {
		if (context === "models") {
			return (
				<Link
					to="/w/$workspaceSlug/admin/usage"
					params={{ workspaceSlug }}
					className={buttonVariants({ variant: "outline", size: "sm", className: "mt-2" })}
				>
					Adjust cap
				</Link>
			);
		}
		return (
			<div className="mt-2 flex flex-wrap gap-2">
				{unverifiable && (
					<Link
						to="/w/$workspaceSlug/admin/models"
						params={{ workspaceSlug }}
						className={buttonVariants({ variant: "outline", size: "sm" })}
					>
						Open AI models
					</Link>
				)}
				<Button variant="outline" size="sm" onClick={onEditOwnProviderCap}>
					Adjust cap
				</Button>
			</div>
		);
	}
	// Only the host can price a shared model, and on the models page the purposes to switch are
	// already below the banner.
	if (unverifiable || context === "models") {
		return null;
	}
	return (
		<Link
			to="/w/$workspaceSlug/admin/models"
			params={{ workspaceSlug }}
			className={buttonVariants({ variant: "outline", size: "sm", className: "mt-2" })}
		>
			Open AI models
		</Link>
	);
}
