import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectDismissed, expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";

import { WorkspacePerPersonResultsSettings } from "./WorkspacePerPersonResultsSettings";

const meta = {
	component: WorkspacePerPersonResultsSettings,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceName: "Hephaestus",
		state: { status: "ready", enabled: false, canTurnOn: true, pending: false },
		onChange: fn(),
	},
} satisfies Meta<typeof WorkspacePerPersonResultsSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

const SWITCH = "Per-person results for admins";

/** Off by default. An owner turning it on is asked first, and told who then sees what. */
export const Off: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText(/and cannot filter by person/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		const inDialog = within(dialog);
		await expect(
			inDialog.getByRole("heading", {
				name: "Show each person’s results to admins of Hephaestus?",
			}),
		).toBeVisible();
		await expect(inDialog.getByText(/Members see that this is on/u)).toBeVisible();
		await expect(args.onChange).not.toHaveBeenCalled();
		await userEvent.click(inDialog.getByRole("button", { name: "Show per person" }));
		await expect(args.onChange).toHaveBeenCalledWith(true);
		await expectDismissed("alertdialog");
	},
};

/** An admin who is not an owner cannot turn it on, and is told who can. */
export const OffForAnAdmin: Story = {
	args: { state: { status: "ready", enabled: false, canTurnOn: false, pending: false } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Only a workspace owner can turn this on.")).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(screen.queryByRole("alertdialog")).toBeNull();
		await expect(args.onChange).not.toHaveBeenCalled();
	},
};

/** On, any admin turns it off in one step: the narrower view needs no confirmation. */
export const On: Story = {
	args: { state: { status: "ready", enabled: true, canTurnOn: false, pending: false } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText(/Any admin can turn this off/u)).toBeVisible();
		await expect(canvas.queryByText("Only a workspace owner can turn this on.")).toBeNull();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(args.onChange).toHaveBeenCalledWith(false);
	},
};

export const Saving: Story = {
	args: { state: { status: "ready", enabled: true, canTurnOn: true, pending: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(args.onChange).not.toHaveBeenCalled();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("switch")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { state: { status: "error", error: { status: 503 }, onRetry: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load this setting")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.state.status === "error") {
			await expect(args.state.onRetry).toHaveBeenCalledOnce();
		}
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
