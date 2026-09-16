import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { CatalogGroupAdoptionPreview } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/core/detail-drawer/DetailDrawerStack";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { withPageBehind } from "@/stories/decorators";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectSettledVisible, settledDrawerPanel } from "@/test/overlay";
import { expectNoPanelOverflow } from "@/test/reflow";

import { GroupAdoptionPanel } from "./GroupAdoptionPanel";

const practice = {
	slug: "describe-what-and-why",
	availability: "AVAILABLE" as const,
	etag: '"practice-preview"',
	initialAutonomy: "HUMAN_APPROVAL" as const,
	sourceReviewRuleFingerprint: mockAuthorDeclaredEvidenceValidation.reviewRuleFingerprint,
	group: { slug: "review-ready-work", disposition: "CREATE_CATALOG_GROUP" as const },
	definition: {
		name: "Describe what changed and why",
		artifactKind: "scm.pull_request" as const,
		bindings: [mockPullRequestBinding],
		criteria: "Confirm the pull request explains both the change and its motivation.",
		automatedReviewPolicy: mockPullRequestPolicy,
		automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
		groupSlug: "review-ready-work",
	},
};

const preview: CatalogGroupAdoptionPreview = {
	slug: "review-ready-work",
	definition: {
		name: "Review-ready work",
		description: "Practices that make proposed changes easier to understand and review.",
	},
	disposition: "CREATE_CATALOG_GROUP",
	etag: '"group-preview"',
	actions: [
		{ slug: "describe-what-and-why", action: "ADD" },
		{ slug: "focused-changes", action: "KEEP" },
		{ slug: "clear-context", action: "BLOCKED" },
	],
	practices: [
		practice,
		{
			...practice,
			slug: "focused-changes",
			availability: "ADOPTED",
			definition: { ...practice.definition, name: "Keep changes focused" },
		},
		{
			...practice,
			slug: "clear-context",
			availability: "SLUG_CONFLICT",
			definition: { ...practice.definition, name: "Provide clear context" },
		},
	],
};

const ready = (
	over: Partial<CatalogGroupAdoptionPreview> = {},
	action: "idle" | "adding" | "stale" = "idle",
) => ({ status: "ready", preview: { ...preview, ...over }, action }) as const;

const meta = {
	title: "Workspace admin/Practice adoption/Group panel",
	component: GroupAdoptionPanel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		state: ready(),
		onConfirm: fn(),
		onOpenPractice: fn(),
	},
	argTypes: {
		// A discriminated union renders as a free-text box, which cannot produce a valid value.
		state: { control: false },
	},
	render: (args) => (
		<Stateful initial={[{ kind: "group", id: preview.slug }]}>
			{(stack, setStack) => (
				<DetailDrawerStack stack={stack} onClose={(depth) => setStack(stack.slice(0, depth))}>
					{(_entry, level) => <GroupAdoptionPanel {...args} nested={level.nested} />}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof GroupAdoptionPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MixedOutcomes: Story = {
	play: async ({ args }) => {
		const confirm = await screen.findByRole("button", { name: "Add 1 practice" });
		await expectSettledVisible(confirm);
		await expect(screen.getByText("Adds")).toBeVisible();
		await expect(screen.getByText("Already here")).toBeVisible();
		await expect(screen.getByText("Blocked")).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: /Describe what changed and why/u }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("describe-what-and-why");
	},
};

export const RestoreDeletedGroup: Story = {
	args: {
		state: ready({
			disposition: "REUSE_EXISTING_GROUP",
			actions: [
				{ slug: "describe-what-and-why", action: "MOVE_TO_GROUP" },
				{ slug: "focused-changes", action: "MOVE_TO_GROUP" },
				{ slug: "clear-context", action: "KEEP" },
			],
		}),
	},
	play: async ({ args }) => {
		const confirm = await screen.findByRole("button", { name: "Restore group" });
		await expectSettledVisible(confirm);
		await expect(screen.getAllByText("Moves back")).toHaveLength(2);
		await userEvent.click(confirm);
		await expect(args.onConfirm).toHaveBeenCalledOnce();
	},
};

export const NothingToChange: Story = {
	args: {
		state: ready({
			actions: preview.actions.map(({ slug }) => ({ slug, action: "KEEP" as const })),
		}),
	},
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Add 0 practices" }));
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
		await expect(screen.getByRole("heading", { name: "Practice group" })).toBeVisible();
	},
};

export const FailedToLoad: Story = {
	args: { state: { status: "error", error: new Error("offline"), onRetry: fn() } },
	play: async ({ args }) => {
		const retry = await screen.findByRole("button", { name: "Retry" });
		await expectSettledVisible(retry);
		await userEvent.click(retry);
		const { state } = args;
		await expect(state.status === "error" && state.onRetry).toHaveBeenCalledOnce();
	},
};

/** The panel stays open with the refreshed plan rather than closing. */
export const PlanWentStale: Story = {
	args: { state: ready({}, "stale") },
	play: async () => {
		const alert = await screen.findByRole("alert");
		await expectSettledVisible(alert);
		await expect(alert.textContent).toContain("The catalog changed while you were reading");
		await expect(screen.getByRole("button", { name: "Add 1 practice" })).toBeEnabled();
	},
};

export const Adding: Story = {
	args: { state: ready({}, "adding") },
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Adding…" }));
	},
};

export const LongContent: Story = {
	args: {
		state: ready({
			definition: {
				name: "Decisions, documentation, and long-lived operational knowledge",
				description:
					"Practices covering how a team records the reasoning behind a change, keeps operational runbooks current, and makes the resulting knowledge findable long after the original authors have moved on.",
			},
		}),
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
