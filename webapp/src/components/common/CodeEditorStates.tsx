import type { ErrorComponentProps } from "@tanstack/react-router";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useSpinDelay } from "@/lib/use-spin-delay";

/*
 * The code editor's states while Monaco is not ready. They live apart from `CodeEditor`, so their
 * stories render them without bundling the editor that `CodeEditor` loads.
 */

export function CodeEditorError({ reset }: ErrorComponentProps) {
	return (
		<div className="space-y-3 p-3">
			<Alert variant="destructive" role="alert">
				<AlertTitle>The editor could not load</AlertTitle>
				<AlertDescription>
					Your script is still in the form. Save the practice, then reload the page to edit the
					script.
				</AlertDescription>
			</Alert>
			<Button type="button" variant="outline" onClick={reset}>
				Retry
			</Button>
		</div>
	);
}

export function CodeEditorSkeleton() {
	const visible = useSpinDelay(true, { delay: 1000, minDuration: 0 });
	return (
		<div className="h-full space-y-3 p-3" aria-busy>
			<span className="sr-only">Loading the editor…</span>
			{visible && (
				<div className="space-y-3" aria-hidden>
					<Skeleton className="h-4 w-3/4" />
					<Skeleton className="h-4 w-1/2" />
					<Skeleton className="h-4 w-2/3" />
					<Skeleton className="h-4 w-1/3" />
				</div>
			)}
		</div>
	);
}
