import type { Meta, StoryObj } from "@storybook/react-vite";

import type { CatalogEntryStatus } from "@/api/types.gen";
import { expectNoOverflowingElement } from "@/test/reflow";

import { CuratedEntryBadges } from "./CuratedEntryBadges";

const status = (overrides: Partial<CatalogEntryStatus> = {}): CatalogEntryStatus => ({
	etag: "tag",
	state: "FROM_HEPHAESTUS",
	changeKind: "NONE",
	offered: true,
	...overrides,
});

const meta = {
	component: CuratedEntryBadges,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { kind: "practice", status: status() },
} satisfies Meta<typeof CuratedEntryBadges>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Customized: Story = { args: { status: status({ state: "EDITED_HERE" }) } };

export const UpdateChangesReviewBehavior: Story = {
	args: { status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }) },
};

export const UpdateWordingOnly: Story = {
	args: { status: status({ state: "UPDATE_WAITING", changeKind: "WORDING" }) },
};

export const NoHephaestusDefault: Story = { args: { status: status({ state: "YOURS" }) } };

export const RemovedFromDefaults: Story = {
	args: { status: status({ state: "NO_LONGER_SHIPPED" }) },
};

export const Excluded: Story = {
	args: { status: status({ offered: false }), kind: "group" },
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
