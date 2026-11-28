import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { CITATIONS, UNAVAILABLE_CITATION } from "./fixtures";
import { TeamCitations } from "./TeamCitations";

const meta = {
	component: TeamCitations,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { citations: CITATIONS, anchorLink: () => <a href="#version-1" /> },
} satisfies Meta<typeof TeamCitations>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each cited place opens in its reviewed version. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("link")).toHaveLength(2);
	},
};

/** Without a link to give, each place is named and opens nothing. */
export const NotLinked: Story = {
	args: { anchorLink: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Students × Define")).toBeVisible();
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

/** No citations: nothing renders, not an empty heading. */
export const Empty: Story = {
	args: { citations: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", { name: "Cited in the work" })).toBeNull();
	},
};

/** A place whose source is gone: no label and no link. */
export const SourceGone: Story = {
	args: { citations: [UNAVAILABLE_CITATION] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No longer available")).toBeVisible();
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

/** A long label wraps at the reflow width. */
export const LongLabel: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	args: {
		citations: CITATIONS.slice(0, 1).map((citation) => ({
			...citation,
			anchor: citation.anchor && {
				...citation.anchor,
				label:
					"Returning students who study part time × Prototype and test with the second user group",
			},
		})),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Returning students/u })).toBeVisible();
	},
};
