import type { Meta, StoryObj } from "@storybook/react";

import { hoursBefore } from "@/stories/story-clock";

import { SilentModeStatusCard } from "./SilentModeStatusCard";

const meta = {
	component: SilentModeStatusCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		settings: { etag: '"0"', silentModeEngaged: false },
	},
} satisfies Meta<typeof SilentModeStatusCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Delivering: Story = {};

export const Engaged: Story = {
	args: {
		settings: {
			etag: '"0"',
			silentModeEngaged: true,
			silentModeChangedAt: hoursBefore(2),
			silentModeChangedBy: "felixtjdietrich",
		},
	},
};

export const Loading: Story = {
	args: { settings: undefined, isLoading: true },
};

export const Unavailable: Story = {
	args: { settings: undefined, isError: true },
};
