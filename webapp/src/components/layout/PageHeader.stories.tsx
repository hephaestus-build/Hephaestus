import type { Meta, StoryObj } from "@storybook/react";
import { Settings2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/test/reflow";

import { PageHeader } from "./PageHeader";

const meta = {
	component: PageHeader,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		icon: <Settings2 />,
		title: "Workspace settings",
		description: "Choose which features workspace members can use.",
	},
} satisfies Meta<typeof PageHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithAction: Story = {
	args: {
		actions: <Button variant="outline">Manage access</Button>,
	},
};

export const LongContent: Story = {
	args: {
		title: "Practice review settings for a workspace with a long display name",
		description:
			"Configure when reviews start, which work is included, and how review policy is inherited across the workspace.",
		actions: <Button variant="outline">Manage review access</Button>,
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 768, 1440] },
	},
	play: expectNoPageOverflow,
};
