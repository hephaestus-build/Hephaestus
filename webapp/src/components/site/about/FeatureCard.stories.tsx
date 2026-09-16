import type { Meta, StoryObj } from "@storybook/react";
import { Code } from "lucide-react";

import { FeatureCard } from "./FeatureCard";

const meta = {
	component: FeatureCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof FeatureCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		feature: {
			icon: <Code className="size-5" />,
			badge: "Core feature",
			title: "Practice feedback",
			description: "Specific feedback on how the work was done",
			content:
				"Hephaestus reviews pull requests, merge requests, and issues against the practices a workspace has chosen. Feedback points to evidence in the work and can suggest what to try next.",
		},
	},
};
