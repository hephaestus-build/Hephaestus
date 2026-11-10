import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import { DangerZoneSection, type DataExportState } from "./DangerZoneSection";

const exportIn = (state: DataExportState, downloading = false) => ({
	state,
	downloading,
	onRequest: fn(),
	onDownload: fn(),
});

const meta = {
	component: DangerZoneSection,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		dataExport: exportIn("idle"),
		deletion: { available: true, deleting: false, onDelete: fn() },
	},
} satisfies Meta<typeof DangerZoneSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** No export asked for yet. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Request export" }));
		await expect(args.dataExport.onRequest).toHaveBeenCalledOnce();
	},
};

/** The request is on its way to the server. */
export const RequestingExport: Story = {
	args: { dataExport: exportIn("requesting") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Requesting export…")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Request export" })).toBeDisabled();
	},
};

/** The server prepares the export; the page reads its status every two seconds. */
export const PreparingExport: Story = {
	args: { dataExport: exportIn("preparing") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/preparing your export/iu)).toBeVisible();
	},
};

/** The export is ready: Download saves it as a JSON file. */
export const ExportReady: Story = {
	args: { dataExport: exportIn("ready") },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Download" }));
		await expect(args.dataExport.onDownload).toHaveBeenCalledOnce();
	},
};

export const Downloading: Story = {
	args: { dataExport: exportIn("ready", true) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Download" })).toBeDisabled();
	},
};

/** The server still works on the export after three minutes: the page stops waiting. */
export const ExportStalled: Story = {
	args: { dataExport: exportIn("stalled") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/taking longer than expected/iu)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Request export again" })).toBeEnabled();
	},
};

/** The server could not prepare the export, or it expired. */
export const ExportFailed: Story = {
	args: { dataExport: exportIn("failed") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/could not prepare your export/iu)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Request export again" })).toBeEnabled();
	},
};

/** Deletion waits for the exact confirmation phrase. */
export const ConfirmDeletion: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Delete account" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		const confirm = dialog.getByRole("button", { name: "Delete account" });
		await userEvent.type(dialog.getByLabelText("Confirmation phrase"), "delete my acc");
		await expect(confirm).toBeDisabled();
		await userEvent.type(dialog.getByLabelText("Confirmation phrase"), "ount");
		await userEvent.click(confirm);
		await expect(args.deletion.onDelete).toHaveBeenCalledOnce();
	},
};

export const Deleting: Story = {
	args: { deletion: { available: true, deleting: true, onDelete: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Deleting…" })).toBeDisabled();
	},
};

/** The signed-in account is not known yet, so the server could not confirm whose account to delete. */
export const DeletionUnavailable: Story = {
	args: { deletion: { available: false, deleting: false, onDelete: fn() } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Delete account" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.type(dialog.getByLabelText("Confirmation phrase"), "delete my account");
		await expect(dialog.getByRole("button", { name: "Delete account" })).toBeDisabled();
	},
};
