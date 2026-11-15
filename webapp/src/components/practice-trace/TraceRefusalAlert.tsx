import type { ReactNode } from "react";

import type { ReviewRequestOutcome } from "@/api/types.gen";
import { ITEM_EVENT_REASON_SENTENCES } from "@/components/practice-vocabulary/item-event-defs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

export interface TraceRefusalAlertProps {
	/** The refused outcome as the server sent it. A `SUBMITTED` one has nothing to show. */
	refusal: ReviewRequestOutcome;
	/**
	 * The way out, such as a `RefusalFixLink`, which the route renders only for a reader who can
	 * apply it.
	 */
	fix?: ReactNode;
}

/**
 * Why the ask started nothing.
 *
 * On the page rather than in a toast: a toast is gone before the reader has finished reading it,
 * and this is the one answer they asked for.
 */
export function TraceRefusalAlert({ refusal, fix }: TraceRefusalAlertProps) {
	return (
		<Alert variant="warning">
			<AlertTitle>No review was started</AlertTitle>
			<AlertDescription>
				<span>
					{refusal.reason === undefined
						? "No reason was given."
						: ITEM_EVENT_REASON_SENTENCES[refusal.reason]}
				</span>
				{fix}
			</AlertDescription>
		</Alert>
	);
}
