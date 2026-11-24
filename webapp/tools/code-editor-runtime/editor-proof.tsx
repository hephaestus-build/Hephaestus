import * as monaco from "monaco-editor";
import { useState } from "react";
import { createRoot } from "react-dom/client";

import { CodeEditor } from "@/components/common/CodeEditor";

import "@/styles.css";

const OTHER_MODEL = monaco.Uri.parse("inmemory://independent-owner/script.ts");

function EditorProof() {
	const [script, setScript] = useState("export const answer: string = 42;");
	const [name, setName] = useState("Original draft");
	const [open, setOpen] = useState(false);
	const [readOnly, setReadOnly] = useState(false);
	const [language, setLanguage] = useState("typescript");
	const [diagnostics, setDiagnostics] = useState<number>();
	const [models, setModels] = useState<number>();
	const [editors, setEditors] = useState<number>();
	const [otherPresent, setOtherPresent] = useState(false);
	const [markers, setMarkers] = useState<number>();
	async function checkWorker() {
		const model = monaco.editor
			.getModels()
			.find(({ uri }) => uri.authority === "hephaestus-editor");
		if (model === undefined) {
			throw new Error("The editor has no model");
		}
		const getWorker = await monaco.typescript.getTypeScriptWorker();
		const worker = await getWorker(model.uri);
		const result = await worker.getSemanticDiagnostics(model.uri.toString());
		setDiagnostics(result.length);
	}

	return (
		<main>
			<label>
				Name
				<input value={name} onChange={(event) => setName(event.target.value)} />
			</label>
			<label>
				Read only
				<input
					type="checkbox"
					checked={readOnly}
					onChange={(event) => setReadOnly(event.target.checked)}
				/>
			</label>
			<button
				type="button"
				onClick={() => {
					monaco.editor.createModel("export const sentinel = true;", "typescript", OTHER_MODEL);
					const { create } = monaco.editor;
					monaco.editor.create = (...args) => {
						monaco.editor.create = create;
						throw new Error(`Synthetic editor construction refusal (${args.length} arguments)`);
					};
				}}
			>
				Fail next editor construction
			</button>
			<label>
				Language
				<select value={language} onChange={(event) => setLanguage(event.target.value)}>
					<option value="typescript">TypeScript</option>
					<option value="javascript">JavaScript</option>
				</select>
			</label>
			<button type="button" onClick={() => setOpen(true)}>
				Open editor
			</button>
			<button type="button" onClick={() => setOpen(false)}>
				Hide editor
			</button>
			{open && (
				<CodeEditor
					value={script}
					onChange={setScript}
					readOnly={readOnly}
					language={language}
					ariaLabel="Practice script"
					className="h-100 w-175"
				/>
			)}
			<output aria-label="Script draft">{script}</output>
			<button type="button" onClick={() => setScript("export const reset: number = 'wrong';")}>
				Reset controlled script
			</button>
			<button
				type="button"
				onClick={() => {
					void checkWorker();
				}}
			>
				Check TypeScript worker
			</button>
			<output aria-label="TypeScript diagnostics">{diagnostics}</output>
			<button
				type="button"
				onClick={() =>
					setScript(
						'import { definePracticeScript } from "../lib/practice-script.ts";\nexport default definePracticeScript;',
					)
				}
			>
				Use an unresolved import
			</button>
			<button type="button" onClick={() => setScript("export const = ;")}>
				Use a syntax error
			</button>
			<button
				type="button"
				onClick={() => {
					const model = monaco.editor
						.getModels()
						.find(({ uri }) => uri.authority === "hephaestus-editor");
					setMarkers(
						model === undefined
							? undefined
							: monaco.editor.getModelMarkers({ resource: model.uri }).length,
					);
				}}
			>
				Count markers
			</button>
			<output aria-label="Editor markers">{markers}</output>
			<button
				type="button"
				onClick={() => {
					setModels(monaco.editor.getModels().length);
					setEditors(monaco.editor.getEditors().length);
					setOtherPresent(monaco.editor.getModel(OTHER_MODEL) !== null);
				}}
			>
				Count models
			</button>
			<output aria-label="Model count">{models}</output>
			<output aria-label="Editor count">{editors}</output>
			<output aria-label="Other model">{otherPresent ? "present" : "absent"}</output>
		</main>
	);
}
const root = document.getElementById("root");
if (root === null) {
	throw new Error("The runtime check has no root element");
}
createRoot(root).render(<EditorProof />);
