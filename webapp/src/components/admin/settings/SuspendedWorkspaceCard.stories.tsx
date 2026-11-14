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

/** The server refused the resume and says why. The admin can try again. */
export const ResumeFailed: Story = {
	args: { resumeError: "This workspace is being deleted, so it cannot become active again." },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(/could not resume the workspace/iu);
		await expect(canvas.getByRole("button", { name: "Resume workspace" })).toBeEnabled();
	},
};
