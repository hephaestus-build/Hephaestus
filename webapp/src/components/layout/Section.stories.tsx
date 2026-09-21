import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { Button } from "@/components/ui/button";

import { Section } from "./Section";

const meta = {
	component: Section,
	parameters: { layout: "padded" },
	args: {
		title: "Review guidance",
		description: "What the reviewer looks for, and what the author should have written.",
		children: <p className="text-sm">Section body.</p>,
	},
	tags: ["autodocs"],
} satisfies Meta<typeof Section>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// Resolving by role AND name is the assertion: `region` only takes a name from
		// `aria-labelledby`, so this fails unless the component wired the heading to the section.
		canvas.getByRole("region", { name: "Review guidance" });
	},
};

export const Sizes: Story = {
	render: (args) => (
		<div className="space-y-8">
			<Section {...args} size="md" title="Medium — a section of an admin page" />
			<Section {...args} size="sm" title="Small — a subsection inside a panel" />
		</div>
	),
};

export const WithActions: Story = {
	args: { actions: <Button size="sm">Add practice</Button> },
};

export const NestedLevel: Story = {
	args: { level: 3, size: "sm", title: "A subsection" },
	play: async ({ canvas }) => {
		// A section inside a section is an h3, so the document outline stays truthful.
		await expect(canvas.getByRole("heading", { level: 3, name: "A subsection" })).toBeVisible();
	},
};

export const TitleOnly: Story = {
	args: { description: undefined },
};

export const LongContent: Story = {
	args: {
		title:
			"Decisions, documentation, and long-lived operational knowledge that outlives its authors",
		description:
			"Practices covering how a team records the reasoning behind a change, keeps operational runbooks current, and makes the resulting knowledge findable long after the original authors have moved on.",
		actions: <Button size="sm">Review 12 practices</Button>,
	},
};
