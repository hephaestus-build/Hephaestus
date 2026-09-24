import { LockIcon } from "lucide-react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { stepUpChallengeOf } from "@/lib/problem-detail";

export interface UserViewErrorAlertProps {
	error: unknown;
	onRetry: () => void;
}

/**
 * A refused private read that asks for a fresh sign-in is not a permission failure and must not read
 * as one: the ask itself is a dialog the route opens, and this is what the region says meanwhile.
 */
export function UserViewErrorAlert({ error, onRetry }: UserViewErrorAlertProps) {
	if (stepUpChallengeOf(error) === undefined) {
		return <QueryErrorAlert error={error} title="User view is unavailable" onRetry={onRetry} />;
	}
	return (
		<Alert variant="warning">
			<LockIcon aria-hidden />
			<AlertTitle className="min-w-0 break-words">Confirm your sign-in to keep viewing</AlertTitle>
			<AlertDescription className="min-w-0 break-words">
				This part of the view needs a recent sign-in. Once you have confirmed it, retry the read.
			</AlertDescription>
			<AlertAction>
				<Button type="button" variant="outline" size="sm" onClick={onRetry}>
					Retry
				</Button>
			</AlertAction>
		</Alert>
	);
}
