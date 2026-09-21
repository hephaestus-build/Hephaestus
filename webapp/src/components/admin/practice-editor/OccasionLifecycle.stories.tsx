import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { PracticeWorkTypeDefinitionOptions } from "@/api/types.gen";
import { artifactKindLabel } from "@/lib/artifact-kinds";
import {
	mockConversationBinding,
	mockConversationWorkType,
	mockDocumentBinding,
	mockDocumentWorkType,
	mockIssueBinding,
	mockIssueWorkType,
	mockPullRequestBinding,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";

import { OccasionLifecycle } from "./OccasionLifecycle";

const meta = {
	component: OccasionLifecycle,
	args: {
		workType: mockPullRequestWorkType,
		selected: mockPullRequestBinding.signals,
		onToggle: fn(),
		includeDrafts: false,
		onIncludeDraftsChange: fn(),
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	// The strip is controlled, so a story has to hold its state — but the handler it holds calls the
	// spy from `args` before it stores anything. A wrapper that *replaces* `onToggle` instead makes
	// `meta.args`'s `fn()` unreachable: the Actions panel stays empty for a component whose whole job
	// is reporting changes, and no play function in the file can assert what was reported.
	render: (args) => (
		<StatefulPatch initial={{ selected: [...args.selected], includeDrafts: args.includeDrafts }}>
			{(state, patch) => (
				<OccasionLifecycle
					{...args}
					selected={state.selected}
					includeDrafts={state.includeDrafts}
					onToggle={(signal, chosen) => {
						args.onToggle(signal, chosen);
						patch({
							selected: chosen
								? [...state.selected, signal]
								: state.selected.filter((value) => value !== signal),
						});
					}}
					onIncludeDraftsChange={(includeDrafts) => {
						args.onIncludeDraftsChange(includeDrafts);
						patch({ includeDrafts });
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof OccasionLifecycle>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PullRequest: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Starts")).toBeVisible();
		await expect(canvas.getByText("Along the way")).toBeVisible();
		await expect(canvas.getByText("Ends")).toBeVisible();
	},
};

export const Conversation: Story = {
	args: { workType: mockConversationWorkType, selected: mockConversationBinding.signals },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Ends")).toBeNull();
	},
};

const ALL_WORK_TYPES: {
	workType: PracticeWorkTypeDefinitionOptions;
	selected: readonly string[];
}[] = [
	{ workType: mockPullRequestWorkType, selected: mockPullRequestBinding.signals },
	{ workType: mockIssueWorkType, selected: mockIssueBinding.signals },
	{ workType: mockDocumentWorkType, selected: mockDocumentBinding.signals },
	{ workType: mockConversationWorkType, selected: mockConversationBinding.signals },
];

/** Side by side, which is the only way to judge whether one visual language holds across them. */
export const EveryWorkType: Story = {
	render: (args) => (
		<div className="space-y-8">
			{ALL_WORK_TYPES.map(({ workType, selected }) => (
				<section key={workType.artifactKind} className="space-y-2">
					<h3 className="text-sm font-semibold">{artifactKindLabel(workType.artifactKind)}</h3>
					<OccasionLifecycle {...args} workType={workType} selected={selected} />
				</section>
			))}
		</div>
	),
};

/**
 * Asking for a review by hand is not a moment: the wire carries it apart from the occasions, so the
 * strip has nothing to filter out and nothing to apologise for.
 */
export const TheHandAskedReviewIsNotOnTheStrip: Story = {
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await expect(strip.queryByRole("checkbox", { name: /by hand/u })).toBeNull();
		await expect(strip.getAllByRole("checkbox")).toHaveLength(6);
	},
};

/**
 * A moment saved before the work type stopped offering it, drawn from what was saved: the form
 * refuses to save while it is set, so it has to be here to be unticked.
 */
export const AMomentTheWorkTypeNoLongerOffers: Story = {
	args: {
		selected: [...mockPullRequestBinding.signals, "scm.pull_request.manual_review"],
	},
	play: async ({ args, canvas, userEvent }) => {
		const stray = canvas.getByRole("checkbox", { name: /^Review requested by hand/u });
		await expect(stray).toBeChecked();

		await userEvent.click(stray);
		await expect(args.onToggle).toHaveBeenCalledWith("scm.pull_request.manual_review", false);
		await expect(canvas.queryByRole("checkbox", { name: /^Review requested by hand/u })).toBeNull();
	},
};

/** The fault is drawn on the strip, not only in the message. */
export const NoMomentChosen: Story = {
	args: { selected: [], errorId: "practice-bindings-error" },
};

export const Disabled: Story = {
	args: { disabled: true },
};

/**
 * Clicking anywhere on a node toggles it, because the node is the label of a real checkbox.
 *
 * What each control reports is asserted as well as what it draws: the strip talks in signal ids the
 * reader never sees, so a node wired to its neighbour's id would still look right.
 */
export const TogglingMoments: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const merged = canvas.getByRole("checkbox", { name: /^Merged/u });
		await expect(merged).not.toBeChecked();

		await userEvent.click(canvas.getByText("Merged"));
		await expect(canvas.getByRole("checkbox", { name: /^Merged/u })).toBeChecked();
		await expect(args.onToggle).toHaveBeenCalledWith("scm.pull_request.merged", true);

		const drafts = canvas.getByRole("switch", { name: /^Include drafts/u });
		await userEvent.click(drafts);
		await expect(canvas.getByRole("switch", { name: /^Include drafts/u })).toBeChecked();
		await expect(args.onIncludeDraftsChange).toHaveBeenCalledWith(true);
	},
};

/**
 * A description nested inside the label joins the switch's accessible name and puts a `<p>` inside a
 * `<label>`, which no content model allows.
 */
export const DraftsSwitchIsNamedByItsLabelAlone: Story = {
	play: async ({ canvas }) => {
		// Exact string: a prefix match would pass against a name the description had run on to.
		const drafts = canvas.getByRole("switch", { name: "Include drafts" });
		const hint = canvas.getByText(/Off by default/u);
		await expect(drafts).toHaveAccessibleName("Include drafts");
		await expect(drafts).toHaveAccessibleDescription(
			"Off by default: read the work once it is offered as finished.",
		);
		// The description is a paragraph, and it is outside the label rather than inside it.
		await expect(hint.tagName).toBe("P");
		await expect(hint.closest("label")).toBeNull();
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
