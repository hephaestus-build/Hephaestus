import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { CHOICE_POINT } from "./fixtures";
import { TeamChoicePoint } from "./TeamChoicePoint";

const meta = {
	component: TeamChoicePoint,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		choicePoint: CHOICE_POINT,
		anchorLink: () => <a href="#version-1" />,
		onRecordDecision: fn(),
	},
} satisfies Meta<typeof TeamChoicePoint>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The options are proposals; "Record a decision" opens the member's form and records nothing by itself. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(3);
		await expect(
			canvas.getByText("Hephaestus proposes these options. A member records the team’s decision."),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Record a decision" }));
		await expect(args.onRecordDecision).toHaveBeenCalledWith(CHOICE_POINT);
	},
};

/** A reader who cannot record here sees the options alone. */
export const ReadOnly: Story = {
	args: { onRecordDecision: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};
