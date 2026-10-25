import { AlertCircleIcon } from "lucide-react";
import { useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

export interface HephUnavailableProps {
	/**
	 * `retry` shows the launcher again. `reload` is for a panel whose code did not load: the browser
	 * keeps that failure until the page loads again.
	 */
	recovery: "retry" | "reload";
	onRecover: () => void;
}

/**
 * Takes the place of Heph's launcher when the panel fails, so Heph does not vanish without a word.
 * The page under it still works, so Close hides the message until the next page load.
 */
export function HephUnavailable({ recovery, onRecover }: HephUnavailableProps) {
	const [closed, setClosed] = useState(false);
	if (closed) {
		return null;
	}
	return (
		<div className="fixed right-6 bottom-6 z-50 w-72 max-w-[calc(100vw-3rem)] rounded-lg shadow-lg">
			<Alert>
				<AlertCircleIcon />
				<AlertTitle>We could not show Heph</AlertTitle>
				<AlertDescription>
					{recovery === "retry"
						? "Try again. The rest of this page still works."
						: "Reload the page to try again. The rest of this page still works."}
				</AlertDescription>
				<div className="col-start-2 mt-2 flex flex-wrap gap-2">
					<Button type="button" variant="outline" size="sm" onClick={onRecover}>
						{recovery === "retry" ? "Retry" : "Reload page"}
					</Button>
					<Button type="button" variant="ghost" size="sm" onClick={() => setClosed(true)}>
						Close
					</Button>
				</div>
			</Alert>
		</div>
	);
}
