import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen } from "storybook/test";

import { workspacePractices } from "./fixtures";
import { ReviewPracticeLink } from "./ReviewPracticeLink";

const thinControllers = workspacePractices.find((p) => p.slug === "thin-controllers");
if (!thinControllers) {
	throw new Error("The practice fixtures no longer cover thin-controllers");
}

const meta = {
	component: ReviewPracticeLink,
	parameters: { layout: "centered", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		practiceSlug: thinControllers.slug,
		practiceName: thinControllers.name,
		group: { slug: "code-quality", name: "Code quality" },
		practice: thinControllers,
	},
} satisfies Meta<typeof ReviewPracticeLink>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithTheHoverCard: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.hover(canvas.getByRole("link", { name: /Thin controllers/u }));
		await screen.findByText(thinControllers.whyItMatters ?? "");
	},
};

export const WithoutThePracticeRecord: Story = {
	args: { practice: undefined },
	play: async ({ canvas, userEvent }) => {
		const link = canvas.getByRole("link", { name: /Thin controllers/u });
		await expect(link).toHaveAttribute("href", "/w/demo/admin/practices/thin-controllers");
		await userEvent.hover(link);
		await expect(screen.queryByText(thinControllers.whyItMatters ?? "")).not.toBeInTheDocument();
	},
};

export const GroupIsAMarkNotAWord: Story = {
	play: async ({ canvas }) => {
		canvas.getByTitle("Code quality");
		await expect(canvas.getByRole("link")).toHaveAccessibleName("Code quality Thin controllers");
	},
};

export const PracticeWithNoGroup: Story = {
	args: { group: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByTitle("Code quality")).not.toBeInTheDocument();
		canvas.getByRole("link", { name: "Thin controllers" });
	},
};
