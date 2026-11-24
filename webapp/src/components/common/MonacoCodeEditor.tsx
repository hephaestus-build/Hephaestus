import { CatchBoundary } from "@tanstack/react-router";
import * as monaco from "monaco-editor";
import { useEffectEvent, useId, useLayoutEffect, useRef } from "react";
import { flushSync } from "react-dom";

import type { CodeEditorProps } from "./CodeEditor";
import { CodeEditorError } from "./CodeEditorStates";

self.MonacoEnvironment = {
	async getWorker(_workerId, label) {
		const { default: MonacoWorker } = await (label === "typescript" || label === "javascript"
			? import("monaco-editor/language/typescript/ts.worker.js?worker")
			: import("monaco-editor/editor/editor.worker.js?worker"));
		return new MonacoWorker();
	},
};
// The editor has no imported modules; check syntax without reporting unresolved imports.
monaco.typescript.typescriptDefaults.setDiagnosticsOptions({
	...monaco.typescript.typescriptDefaults.getDiagnosticsOptions(),
	noSemanticValidation: true,
	noSuggestionDiagnostics: true,
});

export function MonacoCodeEditor(props: Omit<CodeEditorProps, "className">) {
	const id = useId();
	const modelPath = `inmemory://hephaestus-editor/${id}/script.ts`;

	return (
		<CatchBoundary getResetKey={() => modelPath} errorComponent={CodeEditorError}>
			<EditorSurface {...props} modelPath={modelPath} />
		</CatchBoundary>
	);
}

function EditorSurface({
	modelPath,
	value,
	onChange,
	language = "typescript",
	readOnly = false,
	ariaLabel,
}: Omit<CodeEditorProps, "className"> & { modelPath: string }) {
	const container = useRef<HTMLDivElement>(null);
	const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
	const applyingValue = useRef(false);
	const reportChange = useEffectEvent((updated: string) => {
		if (updated !== value) {
			// Monaco emits outside React's input events. Commit the value before the next edit.
			flushSync(() => onChange(updated));
		}
	});
	const mountEditor = useEffectEvent((element: HTMLDivElement) => {
		const model = monaco.editor.createModel(value, language, monaco.Uri.parse(modelPath));
		let editor: monaco.editor.IStandaloneCodeEditor;
		try {
			editor = monaco.editor.create(element, {
				model,
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
				theme: "vs-dark",
			});
		} catch (error) {
			model.dispose();
			throw error;
		}
		editorRef.current = editor;
		const subscription = model.onDidChangeContent(() => {
			if (!applyingValue.current) {
				reportChange(model.getValue());
			}
		});
		return () => {
			subscription.dispose();
			editor.dispose();
			model.dispose();
			editorRef.current = null;
		};
	});

	useLayoutEffect(() => {
		const element = container.current;
		return element ? mountEditor(element) : undefined;
	}, []);

	useLayoutEffect(() => {
		const editor = editorRef.current;
		const model = editor?.getModel();
		if (!editor || !model) {
			return;
		}

		editor.updateOptions({ readOnly, ariaLabel });
		if (language && language !== model.getLanguageId()) {
			monaco.editor.setModelLanguage(model, language);
		}
		if (value === model.getValue()) {
			return;
		}
		applyingValue.current = true;
		if (readOnly) {
			model.setValue(value);
		} else {
			editor.executeEdits("controlled-value", [
				{ range: model.getFullModelRange(), text: value, forceMoveMarkers: true },
			]);
			editor.pushUndoStop();
		}
		applyingValue.current = false;
	}, [value, language, readOnly, ariaLabel]);

	return <div ref={container} className="size-full" />;
}
