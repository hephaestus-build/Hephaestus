import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, userEvent, waitFor } from "storybook/test";

import { Button } from "@/components/ui/button";
import { Toaster } from "@/components/ui/sonner";
import { toast } from "@/lib/toast";

/**
 * A short message about a write that just settled. The preview mounts the `Toaster`, as `__root.tsx`
 * does for the app, so each story raises a toast from a button. Every toast has a close button. A
 * failure, and a toast that offers Undo, stays until the reader closes it; a plain success leaves
 * after a few seconds.
 */
const meta = {
	component: Toaster,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	beforeEach: () => () => {
		toast.dismiss();
	},
} satisfies Meta<typeof Toaster>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A failed write stays until the reader closes it, so a reader who looked away still finds it. */
export const Failure: Story = {
	render: () => (
		<Button
			onClick={() => {
				toast.error("We could not save the budget. Try again.");
			}}
		>
			Save
		</Button>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Save" }));
		await screen.findByText("We could not save the budget. Try again.");

		await userEvent.click(screen.getByRole("button", { name: "Close message" }));
		await waitFor(async () =>
			expect(screen.queryByText("We could not save the budget. Try again.")).toBeNull(),
		);
	},
};

/** A partial failure reads as a warning and stays like a failure. */
export const PartialFailure: Story = {
	render: () => (
		<Button
			onClick={() => {
				toast.warning("Changed 2 of 3. 1 could not be changed.");
			}}
		>
			Change practices
		</Button>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Change practices" }));
		await screen.findByText("Changed 2 of 3. 1 could not be changed.");
		screen.getByRole("button", { name: "Close message" });
	},
};

/** Undo stays until the reader uses it or closes the message: the way back has no timer. */
export const WithUndo: Story = {
	render: () => (
		<Button
			onClick={() => {
				toast.success("Ada Lovelace is hidden from activity", {
					duration: Number.POSITIVE_INFINITY,
					action: {
						label: "Undo",
						onClick: () => {
							toast.success("Ada Lovelace is shown again");
						},
					},
				});
			}}
		>
			Hide from activity
		</Button>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Hide from activity" }));
		await userEvent.click(await screen.findByRole("button", { name: "Undo" }));
		await screen.findByText("Ada Lovelace is shown again");
	},
};

/** A plain success confirms what the page already shows, so it leaves on its own after 4 seconds. */
export const Success: Story = {
	render: () => (
		<Button
			onClick={() => {
				toast.success("Budget saved");
			}}
		>
			Save
		</Button>
	),
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Save" }));
		await screen.findByText("Budget saved");
		screen.getByRole("button", { name: "Close message" });
	},
};
