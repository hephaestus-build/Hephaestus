import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { AutonomyBadge } from "./AutonomyBadge";

const meta = {
	component: AutonomyBadge,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof AutonomyBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const EveryAutonomy: Story = {
	args: { autonomy: "HUMAN_APPROVAL" },
	render: () => (
		<div className="flex flex-wrap gap-2">
			<AutonomyBadge autonomy="OFF" />
			<AutonomyBadge autonomy="HUMAN_APPROVAL" />
			<AutonomyBadge autonomy="AUTOMATIC" />
		</div>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Off")).toBeVisible();
		await expect(canvas.getByText("Review before sending")).toBeVisible();
		await expect(canvas.getByText("Send automatically")).toBeVisible();
	},
};
