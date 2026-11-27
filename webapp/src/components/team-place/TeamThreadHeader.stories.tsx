import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { REVIEW_THREAD } from "./fixtures";
import { TeamThreadHeader } from "./TeamThreadHeader";

const meta = {
	component: TeamThreadHeader,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		thread: REVIEW_THREAD,
		teamName: "Wayfinding",
		reviewedVersionLink: <a href="#version-1" />,
	},
} satisfies Meta<typeof TeamThreadHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A review discussion names its version, opens it, and says who reads it and from when. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Journey map, version 1" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Open the reviewed version" })).toBeVisible();
		await expect(
			canvas.getByText(
				/read this review discussion\. You see the messages from the time you joined\./u,
			),
		).toBeVisible();
	},
};

/** The reviewed version was withdrawn: no link, and the thread says what that hides. */
export const VersionWithdrawn: Story = {
	args: {
		thread: {
			...REVIEW_THREAD,
			review: { itemTitle: "Journey map", versionSequence: 1, versionState: "WITHDRAWN" },
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Version 1 was withdrawn. Its content is deleted, and messages that cited it are hidden.",
			),
		).toBeVisible();
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

/** Hephaestus does not mentor the team now: one line, with no cause and no member named. */
export const MentorPaused: Story = {
	args: { mentorPaused: true },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Hephaestus is not mentoring this team now. Reviews show their observations.",
			),
		).toBeVisible();
	},
};
