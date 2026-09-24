import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { CatalogPracticePreview } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";

import { PracticeAdoptionPanel, type PracticeAdoptionState } from "./PracticeAdoptionPanel";

const preview: CatalogPracticePreview = {
	slug: "describe-what-and-why",
	availability: "AVAILABLE",
	etag: '"adoption-plan"',
	initialAutonomy: "HUMAN_APPROVAL",
	sourceReviewRuleFingerprint: mockAuthorDeclaredEvidenceValidation.reviewRuleFingerprint,
	group: {
		slug: "review-ready-work",
		disposition: "CREATE_CATALOG_GROUP",
		definition: { name: "Review-ready work", description: "Work prepared for useful review." },
	},
	definition: {
		name: "Describe what changed and why",
		artifactKind: "scm.pull_request",
		bindings: [mockPullRequestBinding],
		criteria: "Confirm the pull request explains both the change and its motivation.",
		deliveryBehavior: { summaryOnly: false },
		automatedReviewPolicy: mockPullRequestPolicy,
		automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
		precomputeScript: "export default { hasDescription: pullRequest.body.length > 0 };",
		whyItMatters: "Reviewers need intent to assess whether the change solves the right problem.",
		whatGoodLooksLike: "A concise summary, motivation, and verification steps.",
		groupSlug: "review-ready-work",
	},
};

type ReadyState = Extract<PracticeAdoptionState, { status: "ready" }>;

const ready = (over: Partial<ReadyState> = {}): ReadyState => ({
	status: "ready",
	preview,
	definitionOptions: mockPracticeDefinitionOptions,
	action: "idle",
	...over,
});

/**
 * The panel has no page of its own, so every story mounts a real drawer over a real page.
 */
const meta = {
	component: PracticeAdoptionPanel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		state: ready(),
		onAdopt: fn(),
	},
	argTypes: {
		// A discriminated union renders as a free-text box, which cannot produce a valid value.
		state: { control: false },
	},
	// Stateful, so Escape, an outside press and the header control really close the panel instead
	// of firing an inert spy.
	render: (args) => (
		<Stateful initial={[{ kind: "practice", id: preview.slug }]}>
			{(stack, setStack) => (
				<DetailDrawerStack stack={stack} onClose={(depth) => setStack(stack.slice(0, depth))}>
					{(_entry, level) => <PracticeAdoptionPanel {...args} nested={level.nested} />}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeAdoptionPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Available: Story = {
	play: async ({ args }) => {
		const adopt = await screen.findByRole("button", { name: "Add practice" });
		await expectSettledVisible(adopt);
		await expect(screen.getByText("Review before sending")).toBeVisible();
		await expect(screen.getByText("Create “Review-ready work”")).toBeVisible();
		await userEvent.click(adopt);
		await expect(args.onAdopt).toHaveBeenCalledOnce();
	},
};

export const ReusesExistingGroup: Story = {
	args: {
		state: ready({
			preview: { ...preview, group: { ...preview.group, disposition: "REUSE_EXISTING_GROUP" } },
		}),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("Join “Review-ready work”"));
	},
};

export const AlreadyAdded: Story = {
	args: { state: ready({ preview: { ...preview, availability: "ADOPTED" } }) },
	play: async () => {
		const open = await screen.findByRole("link", { name: "Open workspace practice" });
		await expectSettledVisible(open);
		await expect(screen.queryByRole("button", { name: "Add practice" })).not.toBeInTheDocument();
		// The workspace copy opens as another level rather than a page, so the reader never leaves
		// the catalog to look at what is already in it.
		await expect(
			new URL(open.getAttribute("href") ?? "", "https://example.test").searchParams.get("detail"),
		).toContain("practice:describe-what-and-why");
	},
};

export const NameUnavailable: Story = {
	args: { state: ready({ preview: { ...preview, availability: "SLUG_CONFLICT" } }) },
	play: async () => {
		// Words, colour and icon all come from one registry entry, so the alert and the chip agree.
		await expectSettledVisible(await screen.findByRole("alert"));
		await expect(screen.getAllByText("Name unavailable")).toHaveLength(2);
		await expect(screen.queryByRole("button", { name: "Add practice" })).not.toBeInTheDocument();
	},
};

export const Adding: Story = {
	args: { state: ready({ action: "adding" }) },
	play: async () => {
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Adding…" }));
	},
};

export const PreviewWentStale: Story = {
	args: { state: ready({ action: "stale" }) },
	play: async () => {
		await expectSettledVisible(
			await screen.findByText("The catalog changed while you were reading"),
		);
	},
};

export const UnassignedAndOff: Story = {
	args: {
		state: ready({
			preview: { ...preview, initialAutonomy: "OFF", group: { disposition: "UNASSIGNED" } },
		}),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("Stay unassigned"));
		await expect(screen.getByText("Off")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		// A skeleton holding the shape the definition will take, so nothing jumps when it lands.
		// It is `aria-hidden`, so the proof is the DOM, not a role.
		const panel = await settledDrawerPanel();
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
		await expect(screen.queryByRole("status")).not.toBeInTheDocument();
	},
};

export const FailedToLoad: Story = {
	args: {
		state: { status: "error", error: new Error("offline"), onRetry: fn() },
	},
	play: async ({ args }) => {
		const retry = await screen.findByRole("button", { name: "Retry" });
		await expectSettledVisible(retry);
		await userEvent.click(retry);
		const { state } = args;
		await expect(state.status === "error" && state.onRetry).toHaveBeenCalledOnce();
	},
};

export const LongContent: Story = {
	args: {
		state: ready({
			preview: {
				...preview,
				definition: {
					...preview.definition,
					criteria:
						"Confirm that the pull request explains the behavior change, the operational constraints that shaped it, the alternatives considered, and the evidence used to verify the result. Stay silent when the change is generated automatically and no meaningful author decision exists.",
				},
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
