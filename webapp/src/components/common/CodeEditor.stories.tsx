import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, waitFor } from "storybook/test";

import { CodeEditorError, CodeEditorSkeleton } from "./CodeEditor";

/**
 * The states the code editor shows while it is not ready. Stories never load the editor itself:
 * `.storybook/main.ts` replaces it with a text area.
 */
const meta = {
	component: CodeEditorError,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { error: new Error("Failed to fetch dynamically imported module"), reset: fn() },
	decorators: [
		(Story) => (
			<div className="h-[400px] w-[640px] max-w-full overflow-hidden rounded-md border">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof CodeEditorError>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The editor could not load. The script stays in the form, and Retry loads the editor again. */
export const LoadError: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("The editor could not load");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.reset).toHaveBeenCalledOnce();
	},
};

/** The editor's lines while it downloads. They appear only after a second, so a fast load shows none. */
export const Loading: Story = {
	render: () => <CodeEditorSkeleton />,
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Loading the editor…").parentElement).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await waitFor(
			async () => expect(canvasElement.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(4),
			{ timeout: 3000 },
		);
	},
};
