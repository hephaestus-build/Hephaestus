import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectNoOverflowingElement } from "@/stories/reflow";

import { WorkspacePracticeEditedInHephaestusNote } from "./WorkspacePracticeEditedInHephaestusNote";

const meta = {
	component: WorkspacePracticeEditedInHephaestusNote,
	tags: ["autodocs"],
	args: {
		workspaceSlug: "acme",
		repository: {
			repositoryItemId: "7",
			nameWithOwner: "acme/practices",
			defaultBranch: "main",
			directory: ".hephaestus/practices",
		},
		onMoveToRepository: fn(),
	},
	argTypes: { repository: { control: false } },
} satisfies Meta<typeof WorkspacePracticeEditedInHephaestusNote>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The note only asks; `WorkspaceMoveToRepositoryDialog` confirms. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Edited in Hephaestus")).toBeVisible();
		await expect(
			canvas.getByText("Move it to acme/practices to change its definition and group there."),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Move to repository" }));
		await expect(args.onMoveToRepository).toHaveBeenCalledTimes(1);
	},
};

/** An admin can set a practice repository, so the action stays, unavailable, with the way to it. */
export const NoPracticeRepository: Story = {
	args: { repository: null },
	play: async ({ args, canvas }) => {
		const move = canvas.getByRole("button", { name: "Move to repository" });
		await expect(move).toHaveAttribute("aria-disabled", "true");
		await expect(move).toHaveAccessibleDescription(
			"To change it in a repository instead, first set the practice repository.",
		);
		// Focusable while unavailable, so a keyboard reaches the reason; a press does nothing.
		move.focus();
		await expect(move).toHaveFocus();
		await userEvent.click(move);
		await expect(args.onMoveToRepository).not.toHaveBeenCalled();
		await expect(canvas.getByRole("link", { name: "practice repository" })).toHaveAttribute(
			"href",
			"/w/acme/admin/practices#practice-repository",
		);
	},
};

/** The server reads the folder from a monitored repository, so one it stopped monitoring cannot serve. */
export const NoLongerMonitored: Story = {
	args: { repository: { repositoryItemId: "7", directory: ".hephaestus/practices" } },
	play: async ({ args, canvas }) => {
		const move = canvas.getByRole("button", { name: "Move to repository" });
		await expect(move).toHaveAttribute("aria-disabled", "true");
		await expect(move).toHaveAccessibleDescription(
			"The workspace no longer monitors the practice repository. Choose another in the practice repository setting.",
		);
		await userEvent.click(move);
		await expect(args.onMoveToRepository).not.toHaveBeenCalled();
	},
};

/** The folder is read at the head of the default branch, which a new repository may not name yet. */
export const DefaultBranchUnknown: Story = {
	args: {
		repository: {
			repositoryItemId: "7",
			nameWithOwner: "acme/practices",
			directory: ".hephaestus/practices",
		},
	},
	play: async ({ canvas }) => {
		const move = canvas.getByRole("button", { name: "Move to repository" });
		await expect(move).toHaveAttribute("aria-disabled", "true");
		await expect(move).toHaveAccessibleDescription(
			"The default branch of acme/practices is not known yet. Try again once the repository has synced.",
		);
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
