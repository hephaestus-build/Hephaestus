import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { mockMergeReviewFields, mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow, expectPanelContentInset } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { catalogPracticeLevel, GUARDED_CATALOG_LEVEL_KINDS } from "./catalog-search";
import { CatalogFormLevel } from "./CatalogFormLevel";
import { CatalogPracticeForm } from "./CatalogPracticeForm";
import { catalogPractice, catalogPracticeDefinition } from "./fixtures";

const groups = [
	{ slug: "communication", name: "Communication" },
	{ slug: "version-control", name: "Version control" },
];

const practice = catalogPractice({
	slug: "clear-pr-description",
	groupSlug: "communication",
	definition: catalogPracticeDefinition({ name: "Write a clear pull request description" }),
});

const meta = {
	component: CatalogPracticeForm,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [1440] },
	},
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		definitionOptions: mockPracticeDefinitionOptions,
		cancel: <LevelCancel />,
		groups,
		isPending: false,
		onSubmit: fn(),
	},
	argTypes: { cancel: { control: false } },
	render: (args) => (
		<Stateful
			initial={[catalogPracticeLevel(args.mode === "edit" ? args.practice.slug : undefined)]}
		>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					guardedKinds={GUARDED_CATALOG_LEVEL_KINDS}
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(entry, level) => (
						<CatalogFormLevel
							kind={entry.kind}
							nested={level.nested}
							path={{
								behind: [{ label: "Practice catalog", depth: 0 }],
								onClose: (depth) => setStack(stack.slice(0, depth)),
							}}
						>
							<CatalogPracticeForm {...args} />
						</CatalogFormLevel>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
} satisfies Meta<typeof CatalogPracticeForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Create: Story = {
	args: { mode: "create" },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const Edit: Story = {
	args: { mode: "edit", practice },
};

export const MergerWithAppliesWhen: Story = {
	args: {
		mode: "edit",
		practice: catalogPractice({
			...practice,
			definition: catalogPracticeDefinition({
				...mockMergeReviewFields,
				about: "MERGER",
				appliesWhen: {
					anyOf: [{ changedPath: ["**/*.swift"] }],
					otherwise: "The change has no Swift code.",
				},
			}),
		}),
	},
};

export const ReviewedByPeople: Story = {
	args: {
		mode: "edit",
		practice: catalogPractice({
			...practice,
			definition: catalogPracticeDefinition({
				reads: { contract: "3.0.0", sources: [] },
				reviewedBy: {
					by: "PEOPLE",
					humanReason: {
						code: "NEEDS_PRODUCT_CONTEXT",
						description: "Only the team knows the product context.",
					},
					limits: [],
				},
			}),
		}),
	},
};

export const CustomScriptWithScan: Story = {
	args: {
		mode: "edit",
		practice: catalogPractice({
			...practice,
			definition: catalogPracticeDefinition({
				script: "export default function practiceScript() { return {}; }",
				scan: {
					itemKind: "scm.file",
					include: ["src/**/*.ts"],
					exclude: ["**/*.test.ts"],
					context: [],
					reads: { contract: "3.0.0", sources: [] },
				},
			}),
		}),
	},
};

export const StaleEdit: Story = {
	args: { mode: "edit", practice, conflict: true, onContinueWithDraft: fn() },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("This practice changed while you were editing")).toBeVisible();
		await expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
	},
};

export const RestoringTheHephaestusDefault: Story = {
	args: {
		mode: "edit",
		practice: catalogPractice({
			...practice,
			state: "EDITED_HERE",
			shipped: catalogPracticeDefinition({ criteria: "The Hephaestus criteria" }),
		}),
		onUseHephaestusVersion: fn(),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args }) => {
		await settledDrawerPanel();
		await userEvent.click(screen.getByRole("button", { name: "Restore Hephaestus default" }));
		const dialog = await screen.findByRole("alertdialog");
		await expect(dialog).toHaveAccessibleDescription(/discards unsaved changes/u);
		await userEvent.click(
			within(dialog).getByRole("button", { name: "Restore Hephaestus default" }),
		);
		await expect(args.onUseHephaestusVersion).toHaveBeenCalledOnce();
	},
};

export const HephaestusUpdateAvailable: Story = {
	args: {
		mode: "edit",
		practice: catalogPractice({
			...practice,
			state: "UPDATE_WAITING",
			changeKind: "REVIEW",
			shipped: catalogPracticeDefinition({ criteria: "The updated default criteria" }),
		}),
		releaseReview: <p>Compare the adopted, saved, and proposed fields.</p>,
		onUseHephaestusVersion: fn(),
		onKeepCurrentDefinition: fn(),
	},
	play: async () => {
		const popup = await settledDrawerPanel();
		await expectPanelContentInset(popup);
		await expect(
			screen.getByText("Compare the adopted, saved, and proposed fields."),
		).toBeVisible();
		await expect(
			screen.queryByRole("button", { name: "Keep saved version" }),
		).not.toBeInTheDocument();
	},
};

export const Submitting: Story = {
	args: { mode: "edit", practice, isPending: true },
};

export const RefreshFailed: Story = {
	args: {
		mode: "edit",
		practice,
		refreshAlert: (
			<QueryErrorAlert
				error={{ status: 503 }}
				title="We could not refresh the practice editor"
				onRetry={fn()}
			/>
		),
	},
	play: async () => {
		await expectPanelContentInset(await settledDrawerPanel());
		await expect(screen.getByText("We could not refresh the practice editor")).toBeVisible();
		await expect(screen.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(screen.getByRole("textbox", { name: "Name *" })).toHaveValue(
			practice.definition.name,
		);
	},
};
