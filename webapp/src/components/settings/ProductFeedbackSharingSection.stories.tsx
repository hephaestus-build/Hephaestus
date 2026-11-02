import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { daysBefore } from "@/stories/story-clock";
import { expectUnavailable } from "@/test/controls";

import { ProductFeedbackSharingSection } from "./ProductFeedbackSharingSection";

const onChange = fn();

const meta = {
	component: ProductFeedbackSharingSection,
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			sharing: {
				filesToGitHub: true,
				publicRepository: "hephaestus-build/Hephaestus",
				allowPublic: true,
				mention: false,
				githubLogin: "octocat",
				decidedAt: daysBefore(3),
			},
			pending: false,
			onChange,
		},
	},
} satisfies Meta<typeof ProductFeedbackSharingSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Withdrawing is one press, and it keeps the mention choice as it was. */
export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: "Public GitHub issues" }));
		await expect(onChange).toHaveBeenCalledWith({ allowPublic: false, mention: false });
		await expect(
			canvas.getByRole("switch", { name: "Mention my GitHub account (@octocat) on public issues" }),
		).not.toBeChecked();
	},
};

/** Before the first choice the mention waits, so a mention change cannot record "private". */
export const NotChosenYet: Story = {
	args: {
		state: {
			status: "ready",
			sharing: {
				filesToGitHub: true,
				publicRepository: "hephaestus-build/Hephaestus",
				mention: false,
				githubLogin: "octocat",
			},
			pending: false,
			onChange,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("switch", { name: "Public GitHub issues" })).not.toBeChecked();
		await expect(canvas.getByText(/You have not chosen yet/u)).toBeVisible();
		await expectUnavailable(canvas.getByRole("switch", { name: /Mention my GitHub account/u }));
	},
};

/** With no GitHub sign-in there is no account to mention, so the switch is not offered. */
export const WithoutGitHubAccount: Story = {
	args: {
		state: {
			status: "ready",
			sharing: {
				filesToGitHub: true,
				publicRepository: "hephaestus-build/Hephaestus",
				allowPublic: false,
				mention: false,
			},
			pending: false,
			onChange,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("switch")).toHaveLength(1);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const LoadError: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your product feedback choices")).toBeVisible();
	},
};
