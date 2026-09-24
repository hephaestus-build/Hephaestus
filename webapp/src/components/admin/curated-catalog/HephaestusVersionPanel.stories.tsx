import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { CatalogEntryStatus } from "@/api/types.gen";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockMergeBinding,
	mockPracticeDefinitionOptions,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/stories/reflow";

import { HephaestusVersionPanel } from "./HephaestusVersionPanel";

const status = (overrides: Partial<CatalogEntryStatus> = {}): CatalogEntryStatus => ({
	etag: "tag",
	state: "FROM_HEPHAESTUS",
	changeKind: "NONE",
	offered: true,
	...overrides,
});

const shipped = {
	name: "Say what changed and why",
	artifactKind: "scm.pull_request" as const,
	bindings: [
		{
			...mockMergeBinding,
			subject: "REVIEWER" as const,
			appliesWhen: {
				absentSays: "the change has no Swift code",
				anyOf: [{ changedPathMatches: ["**/*.swift"] }],
			},
		},
	],
	criteria: "The updated default criteria.",
	whyItMatters: "So a reviewer can start from intent rather than diff archaeology.",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
};

const meta = {
	component: HephaestusVersionPanel,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		kind: "practice",
		status: status(),
		isResetPending: false,
		disabled: false,
		definitionOptions: mockPracticeDefinitionOptions,
		onUseHephaestusVersion: fn(),
		onKeepCurrentDefinition: fn(),
	},
} satisfies Meta<typeof HephaestusVersionPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const UsesDefault: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Uses Hephaestus default")).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: "Apply Hephaestus update" }),
		).not.toBeInTheDocument();
	},
};

export const Customized: Story = {
	args: { status: status({ state: "EDITED_HERE" }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Restore Hephaestus default" })).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: "Keep saved version" }),
		).not.toBeInTheDocument();
	},
};

export const UpdateChangesReviewBehavior: Story = {
	args: { status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }), shipped },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/would change review rules/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Review Hephaestus update" }));
		await expect(await canvas.findByText("The updated default criteria.")).toBeVisible();
		await expect(canvas.getByText("How it is reviewed")).toBeVisible();
		// The one occasion, with the evidence that review reads: at the merge the threads are read
		// whole, which is what licenses a claim that nobody ever resolved one.
		await expect(canvas.getAllByText("Merged").length).toBeGreaterThan(0);
		await expect(canvas.getAllByText("· captured whole").length).toBeGreaterThan(0);
		await expect(canvas.getByText("reviewer")).toBeVisible();
		await expect(canvas.getByText("Changed path matches **/*.swift")).toBeVisible();
		await expect(canvas.getAllByText("AI-supported mentoring").length).toBeGreaterThan(0);
		await expect(canvas.getAllByText("Pull request details").length).toBeGreaterThan(0);
		await expect(
			canvas.getByText(/Nobody has measured how often this practice is right/u),
		).toBeVisible();
		await expect(
			canvas.getByText("Repository evidence does not establish behavior in a deployed runtime."),
		).toBeVisible();
		// Signals read back under the domain's own label, never as a raw id.
		await expect(canvas.queryByText("scm.pull_request.opened")).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Keep saved version" })).toBeVisible();
	},
};

export const WordingOnlyUpdate: Story = {
	args: { status: status({ state: "UPDATE_WAITING", changeKind: "WORDING" }), shipped },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/review rules would stay the same/iu)).toBeVisible();
	},
};

export const GroupAppearanceUpdate: Story = {
	args: {
		kind: "group",
		status: status({ state: "UPDATE_WAITING", changeKind: "PRESENTATION" }),
		shipped: {
			name: "Review-ready work",
			description: "Make every change easy to review.",
			icon: "PackageCheck",
			color: "sky",
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/would change the group's name, description, icon, or color/u),
		).toBeVisible();
		await expect(canvas.queryByText(/detect/u)).not.toBeInTheDocument();
	},
};

export const NoHephaestusDefault: Story = {
	args: { status: status({ state: "YOURS" }), kind: "group" },
};

export const RemovedFromDefaults: Story = {
	args: { status: status({ state: "NO_LONGER_SHIPPED" }) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Keep saved version as custom" }),
		).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: "Apply Hephaestus update" }),
		).not.toBeInTheDocument();
	},
};

export const TakingTheUpdate: Story = {
	args: {
		status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }),
		shipped,
		isResetPending: true,
	},
};

export const KeepingSavedVersion: Story = {
	args: {
		status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }),
		shipped,
		isKeepPending: true,
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
