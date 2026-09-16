import type { AdminWorkspaceLlmUsage } from "@/api/types.gen";

import { BudgetAmountDialog } from "./BudgetAmountDialog";
import type { Fx } from "./fx";

export interface SetBudgetDialogProps {
	/** `null` keeps the dialog closed. */
	workspace: AdminWorkspaceLlmUsage | null;
	/** From the report envelope, so the field's estimate uses the table's rate. */
	fx?: Fx;
	isCurrentMonth?: boolean;
	isPending: boolean;
	serverError?: string | null;
	onOpenChange: (open: boolean) => void;
	onSubmit: (monthlyLlmBudgetUsd: number | null) => void;
}

/**
 * A workspace's monthly shared-model budget — the spend the *host* pays for. $0 pauses shared-model
 * work immediately; removing it leaves that spend uncapped. Spend on the workspace's own provider is
 * separate money with its own cap.
 */
export function SetBudgetDialog({
	workspace,
	fx,
	isCurrentMonth,
	isPending,
	serverError,
	onOpenChange,
	onSubmit,
}: SetBudgetDialogProps) {
	return (
		<BudgetAmountDialog
			open={workspace !== null}
			title="Set shared-model budget"
			description={
				workspace === null ? null : (
					<>
						What <strong>{workspace.displayName}</strong> ({workspace.workspaceSlug}) can spend on
						shared models each month. When the budget is reached, shared-model work pauses until the
						month resets; the workspace’s own provider is not affected. $0 pauses now.
					</>
				)
			}
			fieldLabel="Monthly budget (USD)"
			submitLabel="Save budget"
			// Otherwise this falls through to the shared dialog's "Remove cap", naming one number twice.
			removeLabel="Remove budget"
			currentValueUsd={workspace?.instanceMonthlyBudgetUsd ?? null}
			fx={fx}
			isCurrentMonth={isCurrentMonth}
			isPending={isPending}
			serverError={serverError}
			onOpenChange={onOpenChange}
			onSubmit={onSubmit}
		/>
	);
}
