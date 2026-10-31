import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, userEvent, waitFor, within } from "storybook/test";

import { Button } from "@/components/ui/button";
import { toast } from "@/lib/toast";

import { noticeCannotOpenWorkspace } from "./cannot-open-workspace";

/**
 * The notice after a link named a workspace the reader cannot open. The workspace gate raises it on
 * arrival; here a button stands in for the arrival. The preview mounts the `Toaster`, as the app does.
 */
const meta = {
	parameters: { layout: "centered" },
	beforeEach: () => () => {
		toast.dismiss();
	},
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const MESSAGE = "You cannot open the workspace “no-such-workspace”.";

/** The gate opened another workspace, and the notice names both. It stays until the reader closes it. */
export const OpenedAnotherWorkspace: Story = {
	render: () => (
		<Button onClick={() => noticeCannotOpenWorkspace("no-such-workspace", "Acme")}>Arrive</Button>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Arrive" }));
		const message = await screen.findByText(MESSAGE);
		await expect(screen.getByText("This is Acme.")).toBeVisible();
		// A screen reader hears it: the toaster is a polite live region.
		await expect(message.closest("[aria-live='polite']")).not.toBeNull();

		await userEvent.click(screen.getByRole("button", { name: "Close message" }));
		await waitFor(async () => expect(screen.queryByText(MESSAGE)).toBeNull());
	},
};

/** No workspace is left to open, so the notice says only what failed. */
export const NoWorkspaceToOpen: Story = {
	render: () => (
		<Button onClick={() => noticeCannotOpenWorkspace("no-such-workspace", undefined)}>
			Arrive
		</Button>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Arrive" }));
		const message = await screen.findByText(MESSAGE);
		const notice = message.closest<HTMLElement>("[data-sonner-toast]");
		await expect(notice).not.toBeNull();
		await expect(within(notice ?? message).queryByText(/^This is /u)).toBeNull();
	},
};
