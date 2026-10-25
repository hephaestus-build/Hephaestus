import { AlertCircleIcon } from "lucide-react";

import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

export interface HephUnavailableProps {
	/** Shows the launcher again, closed. */
	onRetry: () => void;
}

/**
 * Takes the place of Heph's launcher when the panel fails, so Heph does not vanish from the page
 * without a word. Small on purpose: the page under it still works.
 */
export function HephUnavailable({ onRetry }: HephUnavailableProps) {
	return (
		<div className="fixed right-6 bottom-6 z-50 w-80 max-w-[calc(100vw-3rem)] rounded-lg shadow-lg">
			<Alert>
				<AlertCircleIcon />
				<AlertTitle>We could not show Heph</AlertTitle>
				<AlertDescription>Try again. The rest of this page still works.</AlertDescription>
				<AlertAction>
					<Button type="button" variant="outline" size="sm" onClick={onRetry}>
						Retry
					</Button>
				</AlertAction>
			</Alert>
		</div>
	);
}
