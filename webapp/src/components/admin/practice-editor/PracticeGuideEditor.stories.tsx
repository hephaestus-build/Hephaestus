import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { bundledGuidance } from "@/stories/practice-guidance-story-mock-data";
import { StatefulPatch } from "@/stories/stateful";

import { NO_GUIDE } from "./practice-guidance-draft";
import { PracticeGuideEditor } from "./PracticeGuideEditor";

const figureSvg = bundledGuidance.guide.figures["split-order"];

const meta = {
	component: PracticeGuideEditor,
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
	args: { value: NO_GUIDE, onChange: fn(), view: "write", onViewChange: fn() },
	argTypes: {
		// Markdown and its figures as one record: a text box per figure would let them disagree.
		value: { control: false },
	},
	render: (args) => (
		<StatefulPatch initial={{ value: args.value, view: args.view }}>
			{(state, patch) => (
				<PracticeGuideEditor
					{...args}
					value={state.value}
					view={state.view}
					onChange={(value) => {
						args.onChange(value);
						patch({ value });
					}}
					onViewChange={(view) => {
						args.onViewChange(view);
						patch({ view });
					}}
				/>
			)}
		</StatefulPatch>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeGuideEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

/** No guide yet, so there is nothing to remove. */
export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "Guide text" })).toHaveValue("");
		await expect(canvas.queryByRole("button", { name: "Remove guide" })).toBeNull();
		await expect(canvas.queryByRole("list", { name: "Figures" })).toBeNull();
	},
};

/** The bundled guide and its one figure. */
export const WithGuide: Story = {
	args: { value: bundledGuidance.guide },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("figures/split-order.svg")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Remove guide" })).toBeVisible();
	},
};

/** Preview shows the guide as a developer reads it in the Guide tab, with its figure drawn bare. */
export const Preview: Story = {
	args: { value: bundledGuidance.guide },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("tab", { name: "Preview" }));

		await expect(canvas.getByRole("heading", { name: "How to do it" })).toBeVisible();
		await expect(canvas.getAllByRole("img", { name: /^Three changes in order/u })).toHaveLength(2);
	},
};

/**
 * A second file of the same name gets the next free name, and its line goes after the text the
 * cursor was in, on a paragraph of its own.
 */
export const AddFigure: Story = {
	args: {
		value: { markdown: "Split the work in this order.", figures: { "split-order": figureSvg } },
	},
	play: async ({ canvas }) => {
		const file = new File([figureSvg], "Split order.svg", { type: "image/svg+xml" });
		await userEvent.upload(canvas.getByLabelText("SVG file for a figure"), file);

		await waitFor(async () => {
			await expect(canvas.getByRole("textbox", { name: "Guide text" })).toHaveValue(
				"Split the work in this order.\n\n![Describe the figure](figures/split-order-2.svg)",
			);
		});
		await expect(canvas.getByText("figures/split-order-2.svg")).toBeVisible();
	},
};

/** Removing a figure takes the lines that show it out of the text too. */
export const RemoveFigure: Story = {
	args: { value: bundledGuidance.guide },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Remove figure split-order" }));

		const text = canvas.getByRole<HTMLTextAreaElement>("textbox", { name: "Guide text" });
		await expect(text.value).not.toContain("figures/split-order.svg");
		await expect(text.value).toContain("## How to do it");
		await expect(canvas.queryByRole("list", { name: "Figures" })).toBeNull();
	},
};

export const RemoveGuide: Story = {
	args: { value: bundledGuidance.guide },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Remove guide" }));

		await expect(canvas.getByRole("textbox", { name: "Guide text" })).toHaveValue("");
		await expect(canvas.queryByRole("list", { name: "Figures" })).toBeNull();
	},
};

/** At four figures the guide is full, and the editor says what to do about it. */
export const FullOfFigures: Story = {
	args: {
		value: {
			markdown: ["one", "two", "three", "four"]
				.map((name) => `![Figure ${name}](figures/${name}.svg)`)
				.join("\n\n"),
			figures: { one: figureSvg, two: figureSvg, three: figureSvg, four: figureSvg },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Add figure" })).toBeDisabled();
		await expect(
			canvas.getByText("A guide shows at most 4 figures. Remove one to add another."),
		).toBeVisible();
	},
};

/** A refused save names the problem beside the text. */
export const WithError: Story = {
	args: {
		value: { markdown: "", figures: { "split-order": figureSvg } },
		error: "Write the guide, or remove it.",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "Guide text" })).toHaveAccessibleDescription(
			/Write the guide, or remove it\./u,
		);
	},
};
