import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { definitionOf, mockDefinitionOptions } from "@/components/admin/practice-editor/fixtures";
import type { PracticeAdoptionPreview } from "@/components/admin/practices/catalog-adoption";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { expectGenuinelyDisabled } from "@/test/controls";

import { practiceScript } from "./fixtures";
import { PracticeAdoptionPanel, type PracticeAdoptionState } from "./PracticeAdoptionPanel";

const preview: PracticeAdoptionPreview = {
	slug: "describe-what-and-why",
	kind: "scm.pull_request",
	availability: "AVAILABLE",
	etag: '"adoption-plan"',
	initialFeedbackMode: "APPROVE_FIRST",
	offeredDigest: "sha256:describe-what-and-why",
	group: {
		slug: "review-ready-work",
		disposition: "CREATE_CATALOG_GROUP",
		definition: {
			name: "Review-ready work",
			description: "Work prepared for useful review.",
			icon: null,
			color: null,
		},
	},
	definition: definitionOf({
		name: "Describe what changed and why",
		criteria: "Confirm the pull request explains both the change and its motivation.",
		script: practiceScript(),
	}),
};

type ReadyState = Extract<PracticeAdoptionState, { status: "ready" }>;

const ready = (over: Partial<ReadyState> = {}): ReadyState => ({
	status: "ready",
	preview,
	definitionOptions: mockDefinitionOptions,
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
		path: { behind: [{ label: "Practice setup", depth: 0 }], onClose: fn() },
	},
	argTypes: {
		// A discriminated union renders as a free-text box, which cannot produce a valid value.
		state: { control: false },
		path: { control: false },
	},
	// A real stack, so Escape, an outside press and the header control really close the panel instead
	// of firing an inert spy.
	render: (args) => (
		<InLevelStack entry={{ kind: "catalog-practice", id: preview.slug }} path={args.path}>
			{(level) => <PracticeAdoptionPanel {...args} {...level} />}
		</InLevelStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeAdoptionPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Available: Story = {
	play: async ({ args }) => {
		const adopt = await screen.findByRole("button", { name: "Add practice" });
		await expectSettledVisible(adopt);
		await expect(screen.getByText("Approve first")).toBeVisible();
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
		await expect(screen.getAllByText("Name in use")).toHaveLength(2);
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
			preview: {
				...preview,
				initialFeedbackMode: "OFF",
				group: { disposition: "UNASSIGNED" },
			},
		}),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("Stay unassigned"));
		await expect(screen.getByText("Off")).toBeVisible();
		await expect(
			screen.getByText(
				"Off keeps observations and stops feedback on the work, on practice pages, and in conversation.",
			),
		).toBeVisible();
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
		await expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(
			"Loading catalog practice",
		);
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
					...definitionOf(),
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
