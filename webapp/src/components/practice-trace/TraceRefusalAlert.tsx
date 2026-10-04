import type { ReviewRequestOutcome } from "@/api/types.gen";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

import { RefusalFixLink } from "./RefusalFixLink";

export interface TraceRefusalAlertProps {
	/** The refused outcome as the server sent it. A `SUBMITTED` one has nothing to show. */
	refusal: ReviewRequestOutcome;
	workspaceSlug: string;
	canAdminister: boolean;
}

/**
 * Why the ask started nothing.
 *
 * On the page rather than in a toast: a toast is gone before the reader has finished reading it,
 * and this is the one answer they asked for.
 */
export function TraceRefusalAlert({
	refusal,
	workspaceSlug,
	canAdminister,
}: TraceRefusalAlertProps) {
	return (
		<Alert variant="warning">
			<AlertTitle>No review was started</AlertTitle>
			<AlertDescription>
				{/* Verbatim, and the fix link is keyed on the coded reason rather than the prose:
				    the prose is the server's to change. */}
				<span>{refusal.reasonDescription ?? "No reason was given."}</span>
				{refusal.reason && (
					<RefusalFixLink
						workspaceSlug={workspaceSlug}
						reason={refusal.reason}
						canAdminister={canAdminister}
					/>
				)}
			</AlertDescription>
		</Alert>
	);
}
