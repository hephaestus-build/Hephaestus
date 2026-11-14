import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import { LeaveWorkspaceSection } from "./LeaveWorkspaceSection";

const meta = {
	component: LeaveWorkspaceSection,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaces: [
			{ slug: "ase", name: "Applied Software Engineering" },
			{ slug: "intro-course", name: "Intro Course" },
		],
		onLeave: fn(async () => undefined),
		onDismissError: fn(),
	},
} satisfies Meta<typeof LeaveWorkspaceSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The member confirms, and the section asks to leave exactly that workspace. */
export const Leave: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Leave Intro Course" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		dialog.getByText("Leave Intro Course?");
		await userEvent.click(dialog.getByRole("button", { name: "Leave workspace" }));
		await expect(args.onLeave).toHaveBeenCalledWith("intro-course");
	},
};

/** A leave is under way: its row cannot start it again, and the other rows still can. */
export const Leaving: Story = {
	args: { leavingSlug: "ase", onLeave: fn(async () => new Promise<void>(() => undefined)) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Leave Applied Software Engineering" }),
		).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Leave Intro Course" })).toBeEnabled();
	},
};

/** The last owner cannot leave, and the confirmation stays open with the server's reason. */
export const LastOwner: Story = {
	args: {
		leaveError:
			"You are the last owner of ase. Make another member an owner, or delete the workspace.",
		onLeave: fn(async () => Promise.reject(new Error("409"))),
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Leave Applied Software Engineering" }),
		);
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(dialog.getByRole("alert")).toHaveTextContent(/last owner of ase/u);
		await userEvent.click(dialog.getByRole("button", { name: "Leave workspace" }));
		await expect(screen.getByRole("alertdialog")).toBeVisible();
	},
};

/** No membership: the section says so instead of an empty list. */
export const NoWorkspaces: Story = {
	args: { workspaces: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You are not a member of a workspace.")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /^Leave/u })).toBeNull();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^Leave/u })).toBeNull();
	},
};

const retry = fn();

/** The list could not be read: Retry reads it again. */
export const LoadFailed: Story = {
	args: { loadFailure: { retrying: false, onRetry: retry } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("We could not load your workspaces.");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalled();
	},
};
