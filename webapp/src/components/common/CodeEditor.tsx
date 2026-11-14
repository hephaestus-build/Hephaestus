import { CatchBoundary, type ErrorComponentProps } from "@tanstack/react-router";
import { cn } from "cn";
import { lazy, Suspense, useState } from "react";
import { useSpinDelay } from "spin-delay";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

export interface CodeEditorProps {
	value: string;
	onChange: (value: string) => void;
	language?: string;
	className?: string;
	readOnly?: boolean;
	ariaLabel?: string;
}

/** Monaco loads when an editor first renders, so the pages that hold one closed carry none of it. */
const lazyEditor = () =>
	lazy(async () => {
		const { MonacoCodeEditor } = await import("./MonacoCodeEditor");
		return { default: MonacoCodeEditor };
	});

export function CodeEditor({ className, ...editor }: CodeEditorProps) {
	// React keeps a failed import in its lazy component, so Retry renders a new one.
	const [Editor, setEditor] = useState(lazyEditor);
	return (
		<div className={cn("overflow-hidden rounded-md border", className)}>
			<CatchBoundary
				getResetKey={() => 0}
				onCatch={() => setEditor(lazyEditor)}
				errorComponent={CodeEditorError}
			>
				<Suspense fallback={<CodeEditorSkeleton />}>
					<Editor {...editor} />
				</Suspense>
			</CatchBoundary>
		</div>
	);
}

export function CodeEditorError({ reset }: ErrorComponentProps) {
	return (
		<div className="space-y-3 p-3">
			<Alert variant="destructive" role="alert">
				<AlertTitle>The editor could not load</AlertTitle>
				<AlertDescription>
					Your script is still in the form. Try loading the editor again.
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
