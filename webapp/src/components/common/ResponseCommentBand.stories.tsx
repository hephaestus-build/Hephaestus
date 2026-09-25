import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, within } from "storybook/test";

import { ResponseCommentBand } from "./ResponseCommentBand";

const meta = {
	component: ResponseCommentBand,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		name: "What was helpful",
		label: "What worked about this feedback?",
		placeholder: "Optional: what helped, or what you did",
		onSend: fn(),
		onSkip: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto max-w-2xl overflow-hidden rounded-xl border">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ResponseCommentBand>;

export default meta;
type Story = StoryObj<typeof meta>;

/** An optional line: Send sends whatever was typed, an empty line included, and Skip closes. */
export const Optional: Story = {
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: args.label });
		await expect(field).not.toBeRequired();
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSend).toHaveBeenCalledWith({ comment: "" });
		await userEvent.type(field, "   The split was the right call.");
		// Leading blanks never reach the comment.
		await expect(field).toHaveValue("The split was the right call.");
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(args.onSkip).toHaveBeenCalledOnce();
	},
};

/** A reason and a required sentence: Send does nothing until one is typed. */
export const ReasonRequired: Story = {
	args: {
		name: "What was not helpful",
		label: "What was missed?",
		placeholder: "One or two sentences on what is off",
		required: true,
		reasons: [
			{ value: "not-accurate", label: "Not accurate" },
			{ value: "not-useful", label: "Not useful" },
		],
	},
	play: async ({ args, canvas }) => {
		const field = canvas.getByRole("textbox", { name: args.label });
		await expect(field).toBeRequired();
		const reasons = within(canvas.getByRole("group", { name: "Reason" })).getAllByRole("button");
		await expect(reasons.map((reason) => reason.textContent)).toStrictEqual([
			"Not accurate",
			"Not useful",
		]);
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSend).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Not useful" }));
		await userEvent.type(field, "Each of these was already one concern.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onSend).toHaveBeenCalledWith({
			reason: "not-useful",
			comment: "Each of these was already one concern.",
		});
	},
};

/** The comment is on its way: Send says so and every control waits. */
export const Pending: Story = {
	args: {
		isPending: true,
		reasons: [
			{ value: "not-accurate", label: "Not accurate" },
			{ value: "not-useful", label: "Not useful" },
		],
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("button", { name: "Sending…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Skip" })).toBeDisabled();
		await expect(canvas.getByRole("textbox", { name: args.label })).toBeDisabled();
		for (const reason of within(canvas.getByRole("group", { name: "Reason" })).getAllByRole(
			"button",
		)) {
			await expect(reason).toBeDisabled();
		}
	},
};
