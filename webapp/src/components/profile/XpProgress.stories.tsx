import type { Meta, StoryObj } from "@storybook/react";

import { XpProgress } from "@/components/profile/XpProgress";

const meta = {
	component: XpProgress,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	argTypes: {
		currentXP: {
			control: { type: "number", min: 0 },
			description: "XP earned within the current level",
		},
		xpNeeded: {
			control: { type: "number", min: 1 },
			description: "XP needed to reach the next level",
		},
		nextLevel: {
			control: { type: "number", min: 2 },
			description: "The next level number",
		},
		totalXP: {
			control: { type: "number", min: 0 },
			description: "Total lifetime XP accumulated",
		},
		contributingSince: {
			control: "text",
			description: "Formatted date string showing when user started contributing",
		},
	},
} satisfies Meta<typeof XpProgress>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		currentXP: 450,
		xpNeeded: 1000,
		nextLevel: 6,
		totalXP: 5450,
		contributingSince: "May 2022",
		className: "w-[320px]",
	},
};

export const JustStarted: Story = {
	args: {
		currentXP: 0,
		xpNeeded: 150,
		nextLevel: 2,
		totalXP: 0,
		contributingSince: "January 2026",
		className: "w-[320px]",
	},
};

export const AlmostLevelUp: Story = {
	args: {
		currentXP: 950,
		xpNeeded: 1000,
		nextLevel: 10,
		totalXP: 9950,
		contributingSince: "March 2023",
		className: "w-[320px]",
	},
};

export const HighLevel: Story = {
	args: {
		currentXP: 25_000,
		xpNeeded: 50_000,
		nextLevel: 51,
		totalXP: 1_250_000,
		contributingSince: "January 2019",
		className: "w-[320px]",
	},
};

export const WithoutContributingSince: Story = {
	args: {
		currentXP: 200,
		xpNeeded: 400,
		nextLevel: 4,
		totalXP: 950,
		className: "w-[320px]",
	},
};

export const Level3Example: Story = {
	args: {
		currentXP: 45,
		xpNeeded: 400,
		nextLevel: 4,
		totalXP: 495,
		contributingSince: "October 2024",
		className: "w-[320px]",
	},
};
