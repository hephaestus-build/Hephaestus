import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow, expectPanelContentInset } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { curatedPracticeLevel, GUARDED_CURATED_LEVEL_KINDS } from "./curated-catalog-search";
import { CuratedFormLevel } from "./CuratedFormLevel";
import { CuratedPracticeForm, type CuratedPracticeFormInitialValue } from "./CuratedPracticeForm";

const groups = [
	{ slug: "communication", name: "Communication" },
	{ slug: "version-control", name: "Version control" },
];

const initialData: CuratedPracticeFormInitialValue = {
	slug: "clear-pr-description",
	name: "Write a clear pull request description",
	groupSlug: "communication",
	bindings: [mockPullRequestBinding],
	criteria: "Review whether the description explains the purpose, approach, and testing.",
	whyItMatters: "Reviewers should not need to reconstruct the author's intent.",
	whatGoodLooksLike: "The description states why, what changed, and how it was verified.",
	precomputeScript: "export default function precompute() { return {}; }",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	status: {
		etag: "tag",
		state: "FROM_HEPHAESTUS" as const,
		changeKind: "NONE" as const,
		offered: true,
	},
};

const meta = {
	component: CuratedPracticeForm,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [1440] },
	},
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: { definitionOptions: mockPracticeDefinitionOptions, cancel: <LevelCancel /> },
	argTypes: { cancel: { control: false } },
	render: (args) => (
		<Stateful
			initial={[curatedPracticeLevel(args.mode === "edit" ? args.initialData.slug : undefined)]}
		>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					guardedKinds={GUARDED_CURATED_LEVEL_KINDS}
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(entry, level) => (
						<CuratedFormLevel kind={entry.kind} nested={level.nested}>
							<CuratedPracticeForm {...args} />
						</CuratedFormLevel>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
} satisfies Meta<typeof CuratedPracticeForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Create: Story = {
	args: {
		mode: "create",
		groups,
		isPending: false,
		onSubmit: fn(),
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const Edit: Story = {
	args: {
		mode: "edit",
		initialData,
		groups,
		isPending: false,
		onSubmit: fn(),
	},
};

const scopedGate = {
	absentSays: "the change has no Swift code",
	anyOf: [{ changedPathMatches: ["**/*.swift"] }],
};

export const ScopedReviewerEdit: Story = {
	args: {
		mode: "edit",
		initialData: {
			...initialData,
			bindings: [{ ...mockPullRequestBinding, subject: "REVIEWER", appliesWhen: scopedGate }],
		},
		groups,
		isPending: false,
		onSubmit: fn(),
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(
			screen.getByRole("combobox", { name: "Person this practice judges" }),
		).toHaveTextContent("reviewer");
		await expect(screen.getByRole("textbox", { name: "Only review when" })).toHaveValue(
			JSON.stringify(scopedGate, null, 2),
		);
	},
};

export const StaleEdit: Story = {
	args: {
		mode: "edit",
		initialData,
		groups,
		isPending: false,
		conflict: true,
		onContinueWithDraft: fn(),
		onSubmit: fn(),
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("This practice changed while you were editing")).toBeVisible();
		await expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
	},
};

export const HephaestusUpdateAvailable: Story = {
	args: {
		mode: "edit",
		initialData: {
			...initialData,
			status: {
				...initialData.status,
				state: "UPDATE_WAITING" as const,
				changeKind: "DETECTION" as const,
			},
			shipped: {
				name: "Say what changed and why",
				artifactKind: "scm.pull_request",
				bindings: [mockPullRequestBinding],
				criteria: "The updated default criteria",
				automatedReviewPolicy: mockPullRequestPolicy,
				automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
				whyItMatters: "So a reviewer can start from intent rather than diff archaeology.",
			},
		},
		groups,
		isPending: false,
		onUseHephaestusVersion: fn(),
		onKeepCurrentDefinition: fn(),
		onSubmit: fn(),
	},
	play: async () => {
		const popup = await settledDrawerPanel();
		// The version banner is the host's, not the form's: rendered as a sibling of `DrawerBody` it
		// lands directly on the panel, which has no padding, and touches both edges.
		await expectPanelContentInset(popup);
		// The full label, since colour alone cannot carry which kind of update it is.
		await expect(screen.getByText("Hephaestus update available: review rules")).toBeVisible();
		await expect(screen.getByText(/would change review rules/u)).toBeVisible();
		await expect(screen.getByRole("button", { name: "Review Hephaestus update" })).toBeVisible();
		await expect(screen.getByRole("button", { name: "Apply Hephaestus update" })).toBeVisible();
		await expect(screen.getByRole("button", { name: "Keep saved version" })).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: "Review Hephaestus update" }));
		await expect(screen.getByText("Unassigned")).toBeVisible();
		await expect(screen.getAllByText("Not set").length).toBeGreaterThan(0);
	},
};

export const ValidationErrors: Story = {
	args: {
		mode: "create",
		groups,
		isPending: false,
		onSubmit: fn(),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		await settledDrawerPanel();
		await userEvent.click(screen.getByRole("button", { name: "Create practice" }));
		await expect(screen.getByText("Name must be at least 3 characters")).toBeVisible();
		await expect(screen.queryByText("Select at least one trigger event")).not.toBeInTheDocument();
		await expect(screen.getByRole("textbox", { name: /Name/u })).toHaveAttribute(
			"aria-describedby",
			"practice-name-error",
		);
	},
};

export const Submitting: Story = {
	args: {
		mode: "edit",
		initialData,
		groups,
		isPending: true,
		onSubmit: fn(),
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("textbox", { name: /Name/u })).toBeDisabled();
	},
};
