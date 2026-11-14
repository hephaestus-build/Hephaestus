import { render, screen } from "@testing-library/react";
import { userEvent } from "storybook/test";
import { describe, expect, it, vi } from "vitest";

import { CodeEditor, type CodeEditorProps } from "./CodeEditor";

// `src/test/setup-msw.ts` replaces the editor for every other test; this one is about the editor's own boundary.
vi.unmock("@/components/common/CodeEditor");

const download = vi.hoisted(() => ({ failures: 1 }));
vi.mock("./MonacoCodeEditor", () => {
	if (download.failures > 0) {
		download.failures -= 1;
		throw new Error("Failed to fetch dynamically imported module");
	}
	return {
		MonacoCodeEditor: ({ value, ariaLabel }: CodeEditorProps) => (
			<textarea aria-label={ariaLabel} value={value} readOnly />
		),
	};
});

describe("CodeEditor", () => {
	it("keeps a failed editor download in place and loads it again on Retry", async () => {
		render(<CodeEditor value="return 1;" onChange={vi.fn()} ariaLabel="Practice script" />);

		const alert = await screen.findByRole("alert");
		expect(alert.textContent).toContain("The editor could not load");
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));

		const editor = await screen.findByRole("textbox", { name: "Practice script" });
		expect(editor.textContent).toBe("return 1;");
		expect(screen.queryByRole("alert")).toBeNull();
	});
});
