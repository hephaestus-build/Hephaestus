import { RefreshCwIcon } from "lucide-react";

import type { SyncJob } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

import { JOB_TYPE_LABEL, type SyncTriggerType } from "./sync-format";

export interface SyncNowButtonProps {
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
}

/**
 * The manual-sync trigger, and the only one on its card. With one visible trigger, a pending state can
 * only mean *this* trigger — Backfill is a menu item sharing the same mutation, not a second button.
 */
export function SyncNowButton({ onClick, triggeringType = null, activeJob }: SyncNowButtonProps) {
	const disabled = triggeringType != null || activeJob != null;
	const text = activeJob == null ? (triggeringType == null ? "Sync now" : "Starting…") : "Syncing…";

	return (
		<>
			<Button variant="outline" size="sm" onClick={onClick} disabled={disabled}>
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
				{activeJob == null
					? triggeringType == null
						? ""
						: `Starting ${JOB_TYPE_LABEL[triggeringType].toLowerCase()}`
					: `${JOB_TYPE_LABEL[activeJob.type]} in progress`}
			</span>
		</>
	);
}
