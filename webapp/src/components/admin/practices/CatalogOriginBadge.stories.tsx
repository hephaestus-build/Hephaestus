import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { Button } from "@/components/ui/button";
import { ItemGroup } from "@/components/ui/item";

import { CatalogOriginBadge, CatalogOriginNote } from "./CatalogOriginBadge";
import { catalogOrigin, OWN_ORIGIN } from "./fixtures";

const meta = {
	component: CatalogOriginBadge,
	tags: ["autodocs"],
	args: { kind: "practice", origin: catalogOrigin("clear-pr-description") },
} satisfies Meta<typeof CatalogOriginBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MatchesCatalog: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Same as the catalog")).toBeVisible();
		await expect(canvas.queryByRole("button")).not.toBeInTheDocument();
	},
};

export const WrittenHere: Story = {
	args: { origin: OWN_ORIGIN },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/catalog|Edited here/u)).not.toBeInTheDocument();
	},
};

// The label carries the outcome, not just the event: nothing applies a catalog update to a
// workspace copy, so "the catalog changed" on its own invites the opposite reading.
export const UpdateWaiting: Story = {
	args: { origin: catalogOrigin("clear-pr-description", "UPDATE_WAITING") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Catalog update waiting")).toBeVisible();
	},
};

export const EditedHere: Story = {
	args: { origin: catalogOrigin("clear-pr-description", "EDITED_HERE", ["CRITERIA"]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Edited here")).toBeVisible();
		await expect(canvas.queryByText(/declined/iu)).not.toBeInTheDocument();
	},
};

export const NoLongerShipped: Story = {
	args: { origin: catalogOrigin("clear-pr-description", "NO_LONGER_SHIPPED") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No longer in the catalog")).toBeVisible();
	},
};

export const GroupUpdateWaiting: Story = {
	args: { kind: "group", origin: catalogOrigin("communication", "UPDATE_WAITING") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Catalog update waiting")).toBeVisible();
	},
};

export const Explained: Story = {
	args: { origin: catalogOrigin("clear-pr-description", "EDITED_HERE", ["CRITERIA"]) },
	render: (args) => (
		<ItemGroup>
			<CatalogOriginNote
				{...args}
				action={
					<Button size="sm" variant="outline" className="mt-2 self-start">
						Reset to catalog
					</Button>
				}
			/>
		</ItemGroup>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Edited here")).toBeVisible();
		await expect(canvas.getByText(/brings back the catalog’s version/u)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Reset to catalog" })).toBeVisible();
	},
};

// The note names the page where the update is decided, with the title that page carries.
export const UpdateWaitingExplained: Story = {
	args: { origin: catalogOrigin("clear-pr-description", "UPDATE_WAITING") },
	render: (args) => (
		<ItemGroup>
			<CatalogOriginNote {...args} />
		</ItemGroup>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Catalog update waiting")).toBeVisible();
		await expect(canvas.getByText(/until you decide in Catalog updates\./u)).toBeVisible();
	},
};
