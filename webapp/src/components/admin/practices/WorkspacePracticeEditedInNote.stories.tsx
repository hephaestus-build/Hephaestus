import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectNoOverflowingElement } from "@/stories/reflow";

import { WorkspacePracticeEditedInNote } from "./WorkspacePracticeEditedInNote";

const meta = {
	component: WorkspacePracticeEditedInNote,
	tags: ["autodocs"],
	args: {
		repositoryName: "acme/practices",
		sourceCommit: "4f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39",
		onEditInHephaestus: fn(),
	},
} satisfies Meta<typeof WorkspacePracticeEditedInNote>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Edited in acme/practices")).toBeVisible();
		// The short commit id, not the full one the version carries.
		await expect(canvas.getByText("4f2a9c1")).toBeVisible();
		await expect(canvas.queryByText(/4f2a9c1e/u)).toBeNull();
	},
};

/** The note only asks; `WorkspaceEditInHephaestusDialog` confirms. */
export const AsksToEditInHephaestus: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Edit in Hephaestus" }));
		await expect(args.onEditInHephaestus).toHaveBeenCalledOnce();
	},
};

/** The workspace has no practice repository any more, or its name is still loading. */
export const RepositoryNameUnknown: Story = {
	args: { repositoryName: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Edited in the practice repository")).toBeVisible();
	},
};

/** A version from before the practice moved to the repository was not read at a commit. */
export const NoSourceCommit: Story = {
	args: { sourceCommit: null },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Edited in acme/practices")).toBeVisible();
		await expect(canvas.queryByText(/read at commit/u)).toBeNull();
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
