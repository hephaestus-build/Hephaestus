import MonacoEditor, { loader, type EditorProps } from "@monaco-editor/react";
import { CatchBoundary } from "@tanstack/react-router";
import * as monaco from "monaco-editor";
import { useId, useLayoutEffect, useRef } from "react";
import { flushSync } from "react-dom";

import { type CodeEditorProps, CodeEditorError, CodeEditorSkeleton } from "./CodeEditor";

self.MonacoEnvironment = {
	async getWorker(_workerId, label) {
		const { default: MonacoWorker } = await (label === "typescript" || label === "javascript"
			? import("monaco-editor/language/typescript/ts.worker.js?worker")
			: import("monaco-editor/editor/editor.worker.js?worker"));
		return new MonacoWorker();
	},
};
loader.config({ monaco });
// The editor has no imported modules; check syntax without reporting unresolved imports.
monaco.typescript.typescriptDefaults.setDiagnosticsOptions({
	...monaco.typescript.typescriptDefaults.getDiagnosticsOptions(),
	noSemanticValidation: true,
	noSuggestionDiagnostics: true,
});

export function MonacoCodeEditor({
	value,
	onChange,
	language = "typescript",
	readOnly = false,
	ariaLabel,
}: Omit<CodeEditorProps, "className">) {
	const id = useId();
	const modelPath = `inmemory://hephaestus-editor/${id}/script.ts`;
	const currentValue = useRef(value);
	useLayoutEffect(() => {
		currentValue.current = value;
	}, [value]);
	const handleChange: EditorProps["onChange"] = (updated = "") => {
		if (updated !== currentValue.current) {
			// Monaco emits outside React's input events; commit the controlled value before its next edit.
			flushSync(() => onChange(updated));
		}
	};
	return (
		<CatchBoundary
			getResetKey={() => modelPath}
			onCatch={() => {
				// Construction can fail after the library creates a model, before it owns an editor to dispose.
				monaco.editor.getModel(monaco.Uri.parse(modelPath))?.dispose();
			}}
			errorComponent={CodeEditorError}
		>
			<MonacoEditor
				path={modelPath}
				value={value}
				onChange={handleChange}
				language={language}
				loading={<CodeEditorSkeleton />}
				options={{
					minimap: { enabled: false },
					lineNumbers: "on",
					scrollBeyondLastLine: false,
					fontSize: 13,
					tabSize: 2,
					wordWrap: "on",
					readOnly,
					ariaLabel,
					renderLineHighlight: "none",
					overviewRulerLanes: 0,
					hideCursorInOverviewRuler: true,
					scrollbar: { verticalScrollbarSize: 8, horizontalScrollbarSize: 8 },
					padding: { top: 8, bottom: 8 },
					automaticLayout: true,
				}}
				theme="vs-dark"
			/>
		</CatchBoundary>
	);
}
