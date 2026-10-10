import MonacoEditor, { type EditorProps } from "@monaco-editor/react";

import { cn } from "cn";
import { Spinner } from "@/components/ui/spinner";

export interface CodeEditorProps {
	value: string;
	onChange: (value: string) => void;
	language?: string;
	className?: string;
	readOnly?: boolean;
	ariaLabel?: string;
}

interface DiagnosticsOptions {
	noSemanticValidation?: boolean;
	noSuggestionDiagnostics?: boolean;
}

/**
 * The part of the Monaco API that {@link checkSyntaxOnly} uses. `@monaco-editor/react` types its
 * `Monaco` from `monaco-editor/esm/vs/editor/editor.api`, a path that monaco-editor 0.56 does not
 * export, so that type does not resolve.
 */
interface MonacoTypeScript {
	typescript: {
		typescriptDefaults: {
			getDiagnosticsOptions: () => DiagnosticsOptions;
			setDiagnosticsOptions: (options: DiagnosticsOptions) => void;
		};
	};
}

/**
 * The editor holds one file without the modules it imports, so a type check would mark every import,
 * such as a precompute script's `../lib/precompute.ts`, as an error. Syntax errors still show.
 * Monaco keeps these options per language, not per editor, so they apply to every TypeScript model.
 */
function checkSyntaxOnly({ typescript: { typescriptDefaults } }: MonacoTypeScript) {
	typescriptDefaults.setDiagnosticsOptions({
		...typescriptDefaults.getDiagnosticsOptions(),
		noSemanticValidation: true,
		noSuggestionDiagnostics: true,
	});
}

export function CodeEditor({
	value,
	onChange,
	language = "typescript",
	className,
	readOnly = false,
	ariaLabel,
}: CodeEditorProps) {
	const handleChange: EditorProps["onChange"] = (newValue) => {
		onChange(newValue ?? "");
	};

	return (
		<div className={cn("overflow-hidden rounded-md border", className)}>
			<MonacoEditor
				value={value}
				onChange={handleChange}
				beforeMount={checkSyntaxOnly}
				language={language}
				loading={
					<div className="flex h-full items-center justify-center">
						<Spinner className="h-6 w-6" />
					</div>
				}
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
					scrollbar: {
						verticalScrollbarSize: 8,
						horizontalScrollbarSize: 8,
					},
					padding: { top: 8, bottom: 8 },
					automaticLayout: true,
				}}
				theme="vs-dark"
			/>
		</div>
	);
}
