import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { OVERVIEW } from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityRepositoryTable } from "./ActivityRepositoryTable";

const meta = {
	component: ActivityRepositoryTable,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { repositories: OVERVIEW.repositories, providerType: "GITHUB" },
} satisfies Meta<typeof ActivityRepositoryTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Most contributions first, by full path, so two repositories with one name stay apart. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const [, first] = canvas.getAllByRole("row");
		await expect(
			within(first ?? canvas.getByRole("table")).getAllByRole("cell")[0],
		).toHaveTextContent("hephaestus-build/Hephaestus");
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Merge requests" })).toBeVisible();
	},
};

export const Empty: Story = {
	args: { repositories: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("row")).toHaveLength(1);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};
