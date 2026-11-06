import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen } from "storybook/test";

import { InfoTip } from "@/components/common/InfoTip";
import { Button } from "@/components/ui/button";

import { Section } from "./Section";
import { forgetSectionFold } from "./section-folds";

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
			<Section {...args} size="lg" title="Large — a section of a practice surface" />
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
			"Practices cover how a team records the reasoning behind a change. They keep operational runbooks current. They make the resulting knowledge findable long after the original authors have moved on.",
		actions: <Button size="sm">Review 12 practices</Button>,
	},
};

/**
 * An admin page's long section folds: its heading becomes a button that says whether it is open, and
 * the choice stays in this browser. Open by default.
 */
export const Foldable: Story = {
	args: { foldKey: "story:foldable" },
	beforeEach: () => {
		forgetSectionFold("story:foldable");
		return () => forgetSectionFold("story:foldable");
	},
	play: async ({ canvas, userEvent }) => {
		// Still a heading of the region, with the button inside it.
		canvas.getByRole("region", { name: "Review guidance" });
		const fold = canvas.getByRole("button", { name: "Review guidance" });
		await expect(fold).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getByText("Section body.")).toBeVisible();
		await userEvent.click(fold);
		await expect(fold).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.queryByText("Section body.")).not.toBeVisible();
		// The description stays, so a folded section still says what it holds.
		await expect(
			canvas.getByText("What the reviewer looks for, and what the author should have written."),
		).toBeVisible();
		await expect(window.localStorage.getItem("hephaestus:section-folded:story:foldable")).toBe(
			"true",
		);
		await userEvent.keyboard("{Enter}");
		await expect(fold).toHaveAttribute("aria-expanded", "true");
	},
};

/** The explanation stays outside the section heading and does not change its name. */
export const WithInfo: Story = {
	args: { info: <InfoTip label="About review guidance">What a review checks.</InfoTip> },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "Review guidance" })).toBeVisible();
		await expect(canvas.getByRole("region", { name: "Review guidance" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "About review guidance" }));
		await expect(await screen.findByText("What a review checks.")).toBeVisible();
	},
};
