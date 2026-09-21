import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { SlackChannelPasteField } from "./SlackChannelPasteField";

/**
 * The escape hatch behind the combobox: a channel Slack did not list (typically a private one the
 * bot has not been invited to) can still be reached by pasting its link, mention or id. Parsing —
 * and the `invalid` verdict — belongs to the caller; the field just reflects it, showing an inline
 * error only once the text is non-empty and does not parse.
 */
const meta = {
	component: SlackChannelPasteField,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		id: "paste-channel",
		value: "",
		onChange: fn(),
	},
	argTypes: {
		invalid: { control: "boolean", description: "Text is non-empty and does not parse." },
		disabled: { control: "boolean" },
	},
} satisfies Meta<typeof SlackChannelPasteField>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Empty — typing reports each change up to the caller, which owns parsing. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.type(canvas.getByLabelText(/paste a channel link or id/iu), "C");
		await expect(args.onChange).toHaveBeenCalled();
	},
};

/** A valid paste — no error is shown while the text parses to a channel reference. */
export const Valid: Story = {
	args: { value: "https://acme.slack.com/archives/C0974LJBPBK" },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByText(/paste a slack channel url, mention, or/iu),
		).not.toBeInTheDocument();
	},
};

/** Invalid — a stated, non-silent error appears in place. */
export const Invalid: Story = {
	args: { value: "not-a-channel", invalid: true },
	play: async ({ canvas }) => {
		canvas.getByText(/paste a slack channel url, mention, or/iu);
		await expect(canvas.getByLabelText(/paste a channel link or id/iu)).toHaveAttribute(
			"aria-invalid",
			"true",
		);
	},
};

/** Disabled — inert while the surrounding form is busy. */
export const Disabled: Story = {
	args: { value: "C0974LJBPBK", disabled: true },
};
