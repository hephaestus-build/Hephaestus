import type { Meta, StoryObj } from "@storybook/react-vite";
import { CircleAlertIcon, CircleDashedIcon, InfoIcon, ScrollTextIcon } from "lucide-react";
import { expect } from "storybook/test";

import { Button } from "~/components/common/Button";
import { Notice } from "~/components/common/Notice";

const meta = {
	component: Notice,
	tags: ["autodocs"],
	args: {
		icon: CircleDashedIcon,
		title: "Nothing recorded yet",
		children: "Hephaestus follows this work but has not recorded anything about it.",
	},
} satisfies Meta<typeof Notice>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Neutral: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Nothing recorded yet" })).toBeVisible();
	},
};

export const Info: Story = {
	args: { tone: "info", icon: InfoIcon, title: "Sign-in was cancelled" },
};

export const Warning: Story = {
	args: {
		tone: "warning",
		icon: ScrollTextIcon,
		title: "One step left in Hephaestus",
		children: "Read and accept the current notice in the web app, then come back.",
	},
};

export const WithAction: Story = {
	args: {
		tone: "destructive",
		icon: CircleAlertIcon,
		title: "Hephaestus could not be reached",
		children: "Check your connection.",
		action: <Button size="sm">Try again</Button>,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Try again" })).toBeEnabled();
	},
};
