import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { PublicActivitySection } from "./PublicActivitySection";

const meta = {
	component: PublicActivitySection,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", visible: true, pending: false, onVisibleChange: fn() },
	},
} satisfies Meta<typeof PublicActivitySection>;

export default meta;
type Story = StoryObj<typeof meta>;

const SWITCH = "Show me on public activity pages";

/** A person shows by default; one press hides them everywhere, and the switch says so at once. */
export const Visible: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const control = canvas.getByRole("switch", { name: SWITCH });
		await expect(control).toBeChecked();
		await userEvent.click(control);
		await expect(
			args.state.status === "ready" ? args.state.onVisibleChange : undefined,
		).toHaveBeenCalledWith(false);
	},
};

export const Hidden: Story = {
	args: { state: { status: "ready", visible: false, pending: false, onVisibleChange: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		const control = canvas.getByRole("switch", { name: SWITCH });
		await expect(control).not.toBeChecked();
		await userEvent.click(control);
		await expect(
			args.state.status === "ready" ? args.state.onVisibleChange : undefined,
		).toHaveBeenCalledWith(true);
	},
};

/** While a change saves, the switch ignores presses but keeps keyboard focus. */
export const Saving: Story = {
	args: { state: { status: "ready", visible: false, pending: true, onVisibleChange: fn() } },
	play: async ({ canvas, userEvent }) => {
		const control = canvas.getByRole("switch", { name: SWITCH });
		await expect(control).toHaveAttribute("aria-readonly", "true");
		control.focus();
		await userEvent.click(control);
		await expect(control).not.toBeChecked();
		await expect(control).toHaveFocus();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("switch")).toBeNull();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.queryByRole("switch")).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(
			args.state.status === "error" ? args.state.onRetry : undefined,
		).toHaveBeenCalledOnce();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
