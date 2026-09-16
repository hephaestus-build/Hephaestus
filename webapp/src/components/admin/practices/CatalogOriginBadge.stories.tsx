import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, waitFor, within } from "storybook/test";

import { CatalogOriginBadge } from "./CatalogOriginBadge";

const meta = {
	title: "Workspace admin/Practices/Catalog status",
	component: CatalogOriginBadge,
	tags: ["autodocs"],
	args: {
		kind: "practice",
		origin: { slug: "clear-pr-description", link: "IN_SYNC", sourceOffered: true },
	},
} satisfies Meta<typeof CatalogOriginBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The only place that says the relationship is permanent. */
export const MatchesCatalog: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "Same as the catalog" }).focus();
		const tooltip = await within(document.body).findByText(/the catalog never edits your copy/u);
		await waitFor(() => expect(tooltip).toBeVisible());
	},
};

/** No provenance at all — a practice this workspace wrote itself. Still the only silent state. */
export const NoProvenance: Story = {
	args: { origin: null },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).not.toBeInTheDocument();
	},
};

export const CatalogChanged: Story = {
	args: {
		origin: {
			slug: "clear-pr-description",
			link: "UPDATE_AVAILABLE",
			sourceOffered: true,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "Catalog changed, yours did not" }).focus();
		// The label carries the outcome, not just the event: nothing applies a catalog update to a
		// workspace copy, so "the catalog changed" on its own invites the opposite reading.
		const tooltip = await within(document.body).findByText(/Your copy is untouched/u);
		await waitFor(() => expect(tooltip).toBeVisible());
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
		const status = canvas.getByRole("button", { name: "No longer in the catalog" });
		status.focus();
		const tooltip = await within(document.body).findByText(/Yours keeps working exactly as it is/u);
		await waitFor(() => expect(tooltip).toBeVisible());
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
		const status = canvas.getByRole("button", { name: "Catalog changed, yours did not" });
		status.focus();
		const tooltip = await within(document.body).findByText(/different group details/u);
		await waitFor(() => expect(tooltip).toBeVisible());
	},
};
