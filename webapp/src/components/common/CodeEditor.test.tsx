import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { CodeEditor } from "./CodeEditor";

type DiagnosticsOptions = Record<string, boolean>;

/** Monaco's TypeScript defaults for one page: options that every TypeScript model reads. */
let diagnostics: DiagnosticsOptions = {};

const fakeMonaco = {
	typescript: {
		typescriptDefaults: {
			getDiagnosticsOptions: () => diagnostics,
			setDiagnosticsOptions: (options: DiagnosticsOptions) => {
				diagnostics = options;
			},
		},
	},
};

// jsdom cannot run Monaco, so the stand-in hands the component's setup the page's TypeScript
// defaults, the way the real editor does before it creates its model.
vi.mock("@monaco-editor/react", () => ({
	default: ({ beforeMount }: { beforeMount?: (monaco: typeof fakeMonaco) => void }) => {
		beforeMount?.(fakeMonaco);
		return null;
	},
}));

describe("CodeEditor", () => {
	it("checks TypeScript syntax but not types, because the modules a file imports are not loaded", () => {
		diagnostics = { noSyntaxValidation: false, onlyVisible: false };

		render(<CodeEditor value="" onChange={vi.fn()} ariaLabel="Precompute script" />);

		expect(diagnostics).toStrictEqual({
			noSyntaxValidation: false,
			onlyVisible: false,
			noSemanticValidation: true,
			noSuggestionDiagnostics: true,
		});
	});
});
