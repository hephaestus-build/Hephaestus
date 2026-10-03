import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ItemGroup } from "@/components/ui/item";

import { CatalogOriginBadge, CatalogOriginNote } from "./CatalogOriginBadge";

const meta = {
	component: CatalogOriginBadge,
	tags: ["autodocs"],
	args: {
		kind: "practice",
		origin: { slug: "clear-pr-description", link: "IN_SYNC", sourceOffered: true },
	},
} satisfies Meta<typeof CatalogOriginBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MatchesCatalog: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Same as the catalog")).toBeVisible();
		// A badge sits inside accordion triggers, so it must not be a control of its own.
		await expect(canvas.queryByRole("button")).not.toBeInTheDocument();
	},
};

/** No provenance at all — a practice this workspace wrote itself. Still the only silent state. */
export const NoProvenance: Story = {
	args: { origin: null },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/catalog|Edited here/u)).not.toBeInTheDocument();
	},
};

// The label carries the outcome, not just the event: nothing applies a catalog update to a
// workspace copy, so "the catalog changed" on its own invites the opposite reading.
export const CatalogChanged: Story = {
	args: {
		origin: {
			slug: "clear-pr-description",
			link: "UPDATE_AVAILABLE",
			sourceOffered: true,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Catalog changed, yours did not")).toBeVisible();
	},
};

export const UpdateDeclined: Story = {
	args: {
		origin: {
			slug: "clear-pr-description",
			link: "DECLINED",
			sourceOffered: true,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Update declined")).toBeVisible();
	},
};

export const Customized: Story = {
	args: {
		origin: {
			slug: "clear-pr-description",
			link: "LOCALLY_EDITED",
			sourceOffered: true,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Edited here")).toBeVisible();
	},
};

export const NoLongerIncluded: Story = {
	args: {
		origin: {
			slug: "clear-pr-description",
			link: "IN_SYNC",
			sourceOffered: false,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No longer in the catalog")).toBeVisible();
	},
};

export const GroupChanged: Story = {
	args: {
		kind: "group",
		origin: {
			slug: "communication",
			link: "UPDATE_AVAILABLE",
			sourceOffered: true,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Catalog changed, yours did not")).toBeVisible();
	},
};

/** The sentence a badge cannot carry, written out where the practice is read. */
export const Explained: Story = {
	render: (args) => (
		<ItemGroup>
			<CatalogOriginNote {...args} />
		</ItemGroup>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Same as the catalog")).toBeVisible();
		await expect(canvas.getByText(/will not edit your copy without your decision/u)).toBeVisible();
	},
};
