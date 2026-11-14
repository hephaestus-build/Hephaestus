import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import { SuspendedWorkspaceCard } from "./SuspendedWorkspaceCard";

const meta = {
	component: SuspendedWorkspaceCard,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceName: "Applied Software Engineering",
		github: true,
		resuming: false,
		onResume: fn(),
	},
} satisfies Meta<typeof SuspendedWorkspaceCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** An admin reads what suspension stops and resumes the workspace. */
export const Suspended: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			canvas.getByText("Applied Software Engineering is suspended", { exact: false }),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Resume workspace" }));
		await expect(args.onResume).toHaveBeenCalledOnce();
	},
};

/** The resume is under way: the button cannot send it twice. */
export const Resuming: Story = {
	args: { resuming: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Resuming…" })).toBeDisabled();
	},
};

/** A GitLab workspace: no provider suspends it, so the card says nothing about GitHub. */
export const GitLab: Story = {
	args: { github: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/GitHub/u)).toBeNull();
	},
};

/** The server refused the resume and says why. The admin can try again. */
export const ResumeFailed: Story = {
	args: {
		resumeError:
			"GitHub has suspended the Hephaestus app for this workspace. Unsuspend the app in the GitHub settings of the organization or account. The workspace then resumes by itself.",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(/could not resume the workspace/iu);
		await expect(canvas.getByRole("button", { name: "Resume workspace" })).toBeEnabled();
	},
};
