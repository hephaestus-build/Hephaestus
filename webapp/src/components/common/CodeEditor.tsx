import { CatchBoundary } from "@tanstack/react-router";
import { cn } from "cn";
import { lazy, Suspense, useState } from "react";

import { CodeEditorError, CodeEditorSkeleton } from "./CodeEditorStates";

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
	// React keeps a failed import in its lazy component, so Retry renders a new one. Retry helps after a
	// dropped connection; after a deploy the old chunk is gone, so the alert names the reload.
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
