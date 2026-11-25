import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { WorkspaceStartedMessagesSettings } from "./WorkspaceStartedMessagesSettings";

const meta = {
	component: WorkspaceStartedMessagesSettings,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: { status: "ready", enabled: true, pending: false },
		onChange: fn(),
	},
} satisfies Meta<typeof WorkspaceStartedMessagesSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

const SWITCH = "Check-ins and updates";

/** On by default. Turning it off takes one step, because people still get what they ask for. */
export const On: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText(/check-ins that people agree to/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(args.onChange).toHaveBeenCalledWith(false);
	},
};

/** Off, the description says what people still get, and one step turns it on again. */
export const Off: Story = {
	args: { state: { status: "ready", enabled: false, pending: false } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText(/People still get answers/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(args.onChange).toHaveBeenCalledWith(true);
	},
};

export const Saving: Story = {
	args: { state: { status: "ready", enabled: true, pending: true } },
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
