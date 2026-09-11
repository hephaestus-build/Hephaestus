import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { WorkspaceMentorPreferenceNotice } from "./WorkspaceMentorPreferenceNotice";

/**
 * Every sentence here is quoted from `MENTOR_PREFERENCE_COPY`, the one home for the notice's words,
 * so the same fact is never paraphrased between the setup page and Heph's own screen. The link
 * carries `returnTo` so changing the choice lands the reader back in the conversation they left.
 */
const meta = {
	title: "Onboarding/Mentor preference notice",
	component: WorkspaceMentorPreferenceNotice,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { workspaceSlug: "acme", returnTo: "/w/acme/mentor", notice: { reason: "no-ai" } },
} satisfies Meta<typeof WorkspaceMentorPreferenceNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NoAi: Story = {};

export const ChoiceRequired: Story = {
	args: { notice: { reason: "choice-required" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent(
			"Choose how you want to use AI here",
		);
		await expect(canvas.getByRole("link", { name: "Make your AI choice" })).toHaveAttribute(
			"href",
			expect.stringContaining("returnTo=%2Fw%2Facme%2Fmentor"),
		);
	},
};

export const Unavailable: Story = {
	args: { notice: { reason: "unavailable", location: "On-premises" } },
	play: async ({ canvas }) => {
		canvas.getByText(/No Heph model is assigned to On-premises/);
		await expect(canvas.getByRole("link", { name: "Change your AI choice" })).toHaveAttribute(
			"href",
			expect.stringContaining("returnTo=%2Fw%2Facme%2Fmentor"),
		);
	},
};
