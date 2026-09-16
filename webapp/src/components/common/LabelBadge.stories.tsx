import type { Meta, StoryObj } from "@storybook/react";

import { LabelBadge } from "./LabelBadge";

const meta = {
	component: LabelBadge,
	tags: ["autodocs"],
	parameters: {
		layout: "centered",
	},
} satisfies Meta<typeof LabelBadge>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Bug: Story = {
	args: {
		label: "bug",
		color: "d73a4a",
	},
};

export const Enhancement: Story = {
	args: {
		label: "enhancement",
		color: "a2eeef",
	},
};

export const Documentation: Story = {
	args: {
		label: "documentation",
		color: "0075ca",
	},
};

export const GoodFirstIssue: Story = {
	args: {
		label: "good first issue",
		color: "7057ff",
	},
};

export const MultipleBadges: Story = {
	args: {
		label: "bug",
		color: "d73a4a",
	},
	render: (args) => (
		<div className="flex max-w-[600px] flex-wrap gap-2">
			<LabelBadge {...args} />
			<LabelBadge label="enhancement" color="a2eeef" />
			<LabelBadge label="documentation" color="0075ca" />
			<LabelBadge label="good first issue" color="7057ff" />
			<LabelBadge label="help wanted" color="008672" />
			<LabelBadge label="priority: critical" color="ff0000" />
			<LabelBadge label="priority: high" color="ff8800" />
			<LabelBadge label="priority: medium" color="ffcc00" />
			<LabelBadge label="priority: low" color="c2e0c6" />
			<LabelBadge label="status: in progress" color="9e6a03" />
			<LabelBadge label="status: completed" color="0e8a16" />
		</div>
	),
};
