import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import { EmailUnsubscribePage } from "./EmailUnsubscribePage";

const meta = {
	component: EmailUnsubscribePage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: { state: { status: "confirm", onConfirm: fn() } },
} satisfies Meta<typeof EmailUnsubscribePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Pending: Story = { args: { state: { status: "pending", onConfirm: fn() } } };
export const Complete: Story = { args: { state: { status: "complete" } } };
export const Error: Story = { args: { state: { status: "error", onConfirm: fn() } } };
export const IncompleteLink: Story = { args: { state: { status: "invalid" } } };
export const NarrowDark: Story = { globals: { viewport: { value: "reflow" }, theme: "dark" } };
