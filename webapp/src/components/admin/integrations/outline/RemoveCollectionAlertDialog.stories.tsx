import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { OutlineCollection } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";

import { RemoveCollectionAlertDialog } from "./RemoveCollectionAlertDialog";

const collection: OutlineCollection = {
	id: 1,
	collectionId: "3f9a2c10-8b4e-4d2a-9c1f-2b6d5e4a7c88",
	name: "Engineering Handbook",
	documentCount: 42,
	state: "ENABLED",
	syncStatus: "COMPLETE",
	createdAt: new Date("2026-06-01T00:00:00Z"),
};

/**
 * Destructive confirmation for un-mirroring an Outline collection. The copy states the real
 * consequence — every mirrored document is erased from Hephaestus — while making clear the source
 * documents in Outline are untouched. Because the corpus is re-syncable there is no type-to-confirm
 * gate (unlike the Slack twin); a rejected mutation keeps the dialog open to retry.
 *
 * The alert dialog is portalled, so the plays query the document rather than the story canvas.
 */
const meta = {
	component: RemoveCollectionAlertDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		collection,
		onOpenChange: fn(),
		onConfirm: fn(),
	},
} satisfies Meta<typeof RemoveCollectionAlertDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Many mirrored documents — the copy pluralises and states the exact count that will be erased. */
export const Open: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		dialog.getByText(/all 42 mirrored documents/iu);

		await userEvent.click(dialog.getByRole("button", { name: /remove & erase/iu }));
		await expect(args.onConfirm).toHaveBeenCalledWith({ collectionId: collection.collectionId });
	},
};

/** Exactly one document — the copy switches to the singular "its 1 mirrored document". */
export const SingleDoc: Story = {
	args: { collection: { ...collection, documentCount: 1 } },
	play: async () => {
		const dialog = within(await screen.findByRole("alertdialog"));
		dialog.getByText(/its 1 mirrored document/iu);
	},
};

/** A rejected removal keeps the dialog open so the admin can retry. */
export const Rejected: Story = {
	args: {
		onConfirm: fn(async () => {
			throw new Error("outline rejected the removal");
		}),
	},
	play: async () => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: /remove & erase/iu }));
		await expectSettledVisible(await screen.findByRole("alertdialog"));
	},
};

/** Closed — nothing is rendered until a collection is selected for removal. */
export const Closed: Story = {
	args: { collection: null },
	play: async () => {
		await expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
	},
};
