import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { WorkspaceMentorPreferenceNotice } from "./WorkspaceMentorPreferenceNotice";

/**
 * Every sentence here is quoted from `MENTOR_PREFERENCE_COPY`, the one home for the notice's words,
 * so the same fact is never paraphrased between the setup page and Heph's own screen; the choice an
 * `unavailable` notice names takes its title from the registry, so it reads exactly as its card did.
 * The link carries `returnTo` so changing the choice lands the reader back in the conversation they
 * left.
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

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent(
			"Heph is off for you in this workspace",
		);
		await expect(canvas.getByRole("link", { name: "Change your AI choice" })).toHaveAttribute(
			"href",
			expect.stringContaining("returnTo=%2Fw%2Facme%2Fmentor"),
		);
	},
};

export const ChoiceRequired: Story = {
	args: { notice: { reason: "choice-required" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent(
			"Choose which AI may handle your work",
		);
		await expect(canvas.getByRole("link", { name: "Make your AI choice" })).toHaveAttribute(
			"href",
			expect.stringContaining("returnTo=%2Fw%2Facme%2Fmentor"),
		);
	},
};

export const Unavailable: Story = {
	args: { notice: { reason: "unavailable", choice: "NOT_KEPT_ONLY" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent(
			"Heph isn't set up for your AI choice yet",
		);
		// The title is emphasised so its comma does not split the sentence.
		const title = canvas.getByText("Allow providers without content storage", {
			selector: "em",
		});
		await expect(title).toBeVisible();
		await expect(title.parentElement).toHaveTextContent(
			"No Heph model is within Allow providers without content storage yet. Nothing switches you elsewhere — ask a workspace owner, or change your choice.",
		);
		await expect(canvas.getByRole("link", { name: "Change your AI choice" })).toHaveAttribute(
			"href",
			expect.stringContaining("returnTo=%2Fw%2Facme%2Fmentor"),
		);
	},
};
