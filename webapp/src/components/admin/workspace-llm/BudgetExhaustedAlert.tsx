import { Link } from "@tanstack/react-router";
import { TriangleAlert } from "lucide-react";

import type { WorkspaceLlmUsageReport } from "@/api";
import { budgetResetDayLabel, currentMonthUtc } from "@/components/admin/usage/usage-utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";

type BudgetVerdict = WorkspaceLlmUsageReport["ownProviderBudgetVerdict"];

/** `own` is the cap the workspace sets on its own provider; `shared` is the host's budget. */
type CapScope = "shared" | "own";
type PauseReason = "CAP_REACHED" | "NO_PRICE";

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
		NO_PRICE: {
			title: "Your provider cap can't be enforced",
			body: ({ subject }) =>
				`${subject} no price, so the cap can't be checked and your provider is paused. Add a price to resume, or remove the cap.`,
		},
	},
	shared: {
		CAP_REACHED: {
			title: "Shared-model budget reached",
			body: ({ resetDay }) =>
				`Paused until ${resetDay} (UTC), or until your host raises the budget. Practice reviews and Mentor can keep running on your own models.`,
		},
		NO_PRICE: {
			title: "Shared-model spend can't be verified",
			body: ({ subject }) =>
				`${subject} no price, so the budget can't be checked and shared models are paused. Only your host can price them.`,
		},
	},
};

interface PauseCopyParts {
	resetDay: string;
	subject: string;
}

interface BudgetExhaustedAlertProps {
	scope: CapScope;
	verdict: BudgetVerdict;
	/** ISO `yyyy-MM`; defaults to the current UTC month, the only month a live pause can be in. */
	month?: string;
	unpricedEventCount?: number;
	/** Selects the action offered, never a word of the sentence. */
	context: "usage" | "models";
	workspaceSlug: string;
	onEditOwnProviderCap?: () => void;
}

export function BudgetExhaustedAlert({
	scope,
	verdict,
	month = currentMonthUtc(),
	unpricedEventCount,
	context,
	workspaceSlug,
	onEditOwnProviderCap,
}: BudgetExhaustedAlertProps) {
	const noPriceSet = verdict === "UNVERIFIABLE";
	const copy = PAUSE_COPY[scope][noPriceSet ? "NO_PRICE" : "CAP_REACHED"];
	return (
		<Alert variant={scope === "own" ? "destructive" : "warning"} role="alert">
			<TriangleAlert aria-hidden />
			<AlertTitle>{copy.title}</AlertTitle>
			<AlertDescription>
				<p>
					{copy.body({
						resetDay: budgetResetDayLabel(month),
						subject: unpricedRunsSubject(scope, unpricedEventCount),
					})}
				</p>
				<PauseAction
					scope={scope}
					noPriceSet={noPriceSet}
					context={context}
					workspaceSlug={workspaceSlug}
					onEditOwnProviderCap={onEditOwnProviderCap}
				/>
			</AlertDescription>
		</Alert>
	);
}

function unpricedRunsSubject(scope: CapScope, count: number | undefined): string {
	const own = scope === "own";
	if (count === 1) {
		return own ? "1 run on your models has" : "1 shared-model run has";
	}
	if (count != null && count > 1) {
		const n = count.toLocaleString();
		return own ? `${n} runs on your models have` : `${n} shared-model runs have`;
	}
	return own ? "Some runs on your models have" : "Some shared-model runs have";
}

interface PauseActionProps {
	scope: CapScope;
	noPriceSet: boolean;
	context: "usage" | "models";
	workspaceSlug: string;
	onEditOwnProviderCap?: () => void;
}

/** Caps are edited on AI usage and prices on AI models, so each page links out for the other. */
function PauseAction({
	scope,
	noPriceSet,
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
				{noPriceSet && (
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
	if (noPriceSet || context === "models") {
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
