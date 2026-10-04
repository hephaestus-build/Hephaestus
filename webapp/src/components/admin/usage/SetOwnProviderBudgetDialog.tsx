import { BudgetAmountDialog } from "./BudgetAmountDialog";
import type { Fx } from "./fx";

export interface SetOwnProviderBudgetDialogProps {
	open: boolean;
	/** The cap in force today, in USD; `null`/`undefined` means the workspace is uncapped. */
	currentCapUsd?: number | null;
	fx?: Fx;
	isCurrentMonth?: boolean;
	isPending: boolean;
	serverError?: string | null;
	onOpenChange: (open: boolean) => void;
	onSubmit: (monthlyBudgetUsd: number | null) => void;
}

/**
 * Workspace-admin dialog for the cap on spend through the workspace's *own* provider — its own
 * money, so unlike the shared-model budget this one is theirs to set and remove. $0 pauses work on
 * their provider immediately; shared models are capped separately by the host.
 */
export function SetOwnProviderBudgetDialog({
	open,
	currentCapUsd,
	fx,
	isCurrentMonth,
	isPending,
	serverError,
	onOpenChange,
	onSubmit,
}: SetOwnProviderBudgetDialogProps) {
	return (
		<BudgetAmountDialog
			open={open}
			title="Set your provider cap"
			description="The most this workspace can spend on its own provider each month. At the cap, work on your provider pauses until the month resets. Enter $0 to pause now."
			fieldLabel="Monthly cap (USD)"
			currentValueUsd={currentCapUsd ?? null}
			fx={fx}
			isCurrentMonth={isCurrentMonth}
			isPending={isPending}
			serverError={serverError}
			// No `submitLabel`/`removeLabel`: "Save cap" / "Remove cap" are the shared editor's defaults.
			onOpenChange={onOpenChange}
			onSubmit={onSubmit}
		/>
	);
}
