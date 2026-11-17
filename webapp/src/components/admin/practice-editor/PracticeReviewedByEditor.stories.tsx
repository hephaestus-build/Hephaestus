import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import { expectNoOverflowingElement } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { PracticeReviewedByEditor } from "./PracticeReviewedByEditor";
import type { ReviewedByDraft } from "./reviewed-by";

const hephaestus: ReviewedByDraft = {
	by: "HEPHAESTUS",
	humanReason: { description: "" },
	limits: [
		{ description: "Repository evidence does not establish behavior in a deployed runtime." },
	],
};

const meta = {
	component: PracticeReviewedByEditor,
	args: { value: hephaestus, kept: [], onChange: fn() },
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<PracticeReviewedByEditor
					{...args}
					value={value}
					onChange={(next) => {
						args.onChange(next);
						setValue(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeReviewedByEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Hephaestus: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: /^Hephaestus/u })).toBeChecked();
		await expect(
			canvas.getByDisplayValue(
				"Repository evidence does not establish behavior in a deployed runtime.",
			),
		).toBeVisible();
	},
};

export const People: Story = {
	args: {
		value: {
			...hephaestus,
			by: "PEOPLE",
			humanReason: { description: "The trade-off was agreed in a meeting no review can read." },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: /Why do people review it/u })).toHaveValue(
			"The trade-off was agreed in a meeting no review can read.",
		);
		await expect(canvas.queryByRole("button", { name: "Add limit" })).toBeNull();
	},
};

export const Nobody: Story = {
	args: { value: { ...hephaestus, by: "NOBODY" } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("This practice offers guidance without automated review."),
		).toBeVisible();
	},
};

export const SwitchingSaysWhatIsKept: Story = {
	args: {
		value: { ...hephaestus, by: "NOBODY" },
		kept: ["the read sources", "the practice script"],
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Hephaestus keeps the read sources and the practice script unused while it does not review this practice. Choose Hephaestus again to use them.",
			),
		).toBeVisible();
	},
};

export const TheReasonSurvivesADetour: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: /^People/u }));
		await userEvent.type(
			canvas.getByRole("textbox", { name: /Why do people review it/u }),
			"Decided in a meeting.",
		);
		await userEvent.click(canvas.getByRole("radio", { name: /^Nobody/u }));
		await userEvent.click(canvas.getByRole("radio", { name: /^People/u }));
		await expect(canvas.getByRole("textbox", { name: /Why do people review it/u })).toHaveValue(
			"Decided in a meeting.",
		);
	},
};

export const EditingLimitsKeepsFocus: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Add limit" }));
		const added = canvas.getByRole("textbox", { name: "Description for limit 2" });
		await waitFor(async () => expect(added).toHaveFocus());
		await userEvent.type(added, "A stable limit");
		await userEvent.click(canvas.getByRole("button", { name: "Remove limit 2" }));
		await waitFor(async () =>
			expect(canvas.getByRole("textbox", { name: "Description for limit 1" })).toHaveFocus(),
		);
	},
};

export const Invalid: Story = {
	args: {
		value: { ...hephaestus, by: "PEOPLE" },
		error: "Say why people review this practice, in at most 500 characters.",
		errorFocusId: "practice-human-reason",
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("textbox", { name: /Why do people review it/u }),
		).toHaveAccessibleDescription(/Say why people review this practice/u);
	},
};

export const Disabled: Story = {
	args: { disabled: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: /^People/u })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
