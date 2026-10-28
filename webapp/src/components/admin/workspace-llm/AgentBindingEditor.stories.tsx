import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { AgentBindingEditor } from "./AgentBindingEditor";
import { mockAvailableModels } from "./fixtures";

const meta = {
	component: AgentBindingEditor,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		target: { purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
		binding: {
			purpose: "PRACTICE_REVIEW",
			dataHandlingTier: "IN_HOUSE",
			instanceModelId: 2,
			enabled: true,
			ready: true,
			timeoutSeconds: 600,
			maxConcurrentJobs: 3,
			servedTiers: ["IN_HOUSE", "CLOUD"],
		},
		availableModels: mockAvailableModels,
		aiChoiceRequired: false,
		ownProviderAllowed: true,
		practiceReviewsOff: false,
		unusedForCloudMembers: false,
		onSave: fn(),
		onTurnOff: fn(),
	},
} satisfies Meta<typeof AgentBindingEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Empty: Story = {
	args: { binding: undefined, availableModels: [] },
};

export const Loading: Story = {
	args: { pendingWrite: "SAVE" },
};

export const Error: Story = {
	args: { saveError: "This model is no longer shared with the workspace. Choose another model." },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Save assignment for In-house" }));
		await expect(
			canvas.getByText("This model is no longer shared with the workspace. Choose another model."),
		).toBeVisible();
	},
};

/** A Heph answer stops after 15 minutes, so its timeout cannot be set longer. */
export const HephAnswerCeiling: Story = {
	args: {
		target: { purpose: "MENTOR", tier: "IN_HOUSE" },
		binding: {
			purpose: "MENTOR",
			dataHandlingTier: "IN_HOUSE",
			instanceModelId: 2,
			enabled: true,
			ready: true,
			timeoutSeconds: 900,
			maxConcurrentJobs: 3,
			servedTiers: ["IN_HOUSE", "CLOUD"],
		},
	},
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^Advanced/u }));
		const timeout = canvas.getByLabelText(/^Timeout \(seconds\)/u);
		await userEvent.clear(timeout);
		await userEvent.type(timeout, "901");
		await userEvent.click(canvas.getByRole("button", { name: "Save assignment for In-house" }));
		await expect(
			canvas.getByText("Heph stops an answer after 15 minutes, so enter 900 seconds or less."),
		).toBeVisible();
		await expect(args.onSave).not.toHaveBeenCalled();
	},
};
