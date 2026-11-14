import { RefreshCwIcon } from "lucide-react";

import type { SyncJob } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

import { JOB_TYPE_LABEL, type SyncTriggerType } from "./sync-format";

export interface WorkspaceSyncNowButtonProps {
	onClick: () => void;
	/**
	 * The operation this button just started, or `null` when none is in flight.
	 *
	 * A type rather than a boolean because the surface has one trigger for two operations: a backfill
	 * launched from the split menu must announce itself as a backfill, and a flag could only say that
	 * *something* started.
	 */
	triggeringType?: SyncTriggerType | null;
	activeJob?: SyncJob | null;
	/**
	 * Requests the stopping job's own type again. Offered only where the caller can send that type; the
	 * server decides whether the old run is gone, so a still-running job comes back unchanged.
	 */
	onRecover?: () => void;
}

/**
 * The manual-sync trigger, and the only one on its card. With one visible trigger, a pending state can
 * only mean *this* trigger — Backfill is a menu item sharing the same mutation, not a second button.
 */
export function WorkspaceSyncNowButton({
	onClick,
	triggeringType = null,
	activeJob,
	onRecover,
}: WorkspaceSyncNowButtonProps) {
	const stopping = activeJob?.cancelRequested === true;
	const canRecover =
		stopping && activeJob.type !== "INITIAL" && onRecover != null && triggeringType == null;
	const disabled = !canRecover && (triggeringType != null || activeJob != null);
	let text = "Sync now";
	let announcement = "";
	if (activeJob != null && stopping) {
		const noun = activeJob.type === "BACKFILL" ? "backfill" : "sync";
		if (canRecover) {
			text = `Check and retry ${noun}`;
		} else if (triggeringType == null) {
			text = "Stopping…";
		} else {
			text = "Checking…";
		}
		announcement = `${JOB_TYPE_LABEL[activeJob.type]} stopping`;
	} else if (activeJob != null) {
		text = "Syncing…";
		announcement = `${JOB_TYPE_LABEL[activeJob.type]} in progress`;
	} else if (triggeringType != null) {
		text = "Starting…";
		announcement = `Starting ${JOB_TYPE_LABEL[triggeringType].toLowerCase()}`;
	}

	return (
		<>
			<Button
				variant="outline"
				size="sm"
				onClick={canRecover ? onRecover : onClick}
				disabled={disabled}
			>
				{disabled ? (
					<Spinner className="size-4" aria-hidden />
				) : (
					<RefreshCwIcon className="size-4" />
				)}
				{text}
			</Button>
			{/* Announce sync start/progress to assistive tech; a plain button-label swap is not reliably
			announced. Empty when idle, so nothing is spoken until a run begins. Once the server has a job,
			the announcement names *that* job rather than the trigger's intent — a backfill request that
			landed behind a running reconciliation must not report the reconciliation as a backfill. */}
			<span role="status" aria-live="polite" className="sr-only">
				{announcement}
			</span>
		</>
	);
}
