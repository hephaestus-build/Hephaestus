import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import type { CatalogEntryStatus } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow, expectPanelContentInset } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { curatedGroupLevel, GUARDED_CURATED_LEVEL_KINDS } from "./curated-catalog-search";
import { CuratedFormLevel } from "./CuratedFormLevel";
import { CuratedGroupForm } from "./CuratedGroupForm";

const status = (overrides: Partial<CatalogEntryStatus> = {}): CatalogEntryStatus => ({
	etag: "tag",
	state: "FROM_HEPHAESTUS",
	changeKind: "NONE",
	offered: true,
	...overrides,
});

const initialData = {
	slug: "review-ready-work",
	name: "Packaging work for review",
	description: "Make a change cheap to review before you ask for one.",
	icon: "Package",
	color: "sky",
	status: status(),
};

/**
 * A level of the instance catalog's drawer stack, so the catalog stays on screen while a group is
 * written — and so these stories exercise the surface people actually get.
 */
const meta = {
	component: CuratedGroupForm,
	parameters: { layout: "fullscreen", chromatic: { viewports: [1440] } },
	decorators: [withPageBehind],
	args: { isPending: false, onSubmit: fn(), cancel: <LevelCancel /> },
	argTypes: { cancel: { control: false } },
	render: (args) => (
		<Stateful
			initial={[curatedGroupLevel(args.mode === "edit" ? args.initialData.slug : undefined)]}
		>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					guardedKinds={GUARDED_CURATED_LEVEL_KINDS}
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(entry, level) => (
						<CuratedFormLevel kind={entry.kind} nested={level.nested}>
							<CuratedGroupForm {...args} />
						</CuratedFormLevel>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof CuratedGroupForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Create: Story = { args: { mode: "create" } };

export const Edit: Story = { args: { mode: "edit", initialData } };

export const HephaestusUpdateAvailable: Story = {
	play: async () => {
		// The same banner as the practice editor's, and the same way to get it wrong: rendered beside
		// `DrawerBody` instead of inside it, it lands on the unpadded panel.
		await expectPanelContentInset(await settledDrawerPanel());
	},
	args: {
		mode: "edit",
		initialData: {
			...initialData,
			status: status({ state: "UPDATE_WAITING", changeKind: "PRESENTATION" }),
		},
		onUseHephaestusVersion: fn(),
	},
};

export const StaleEdit: Story = {
	args: { mode: "edit", initialData, conflict: true, onContinueWithDraft: fn() },
};

export const Submitting: Story = {
	args: { mode: "edit", initialData, isPending: true },
	play: async () => {
		await expect(screen.getByRole("textbox", { name: /Name/u })).toBeDisabled();
	},
};

export const NarrowViewport: Story = {
	args: { mode: "create" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};
