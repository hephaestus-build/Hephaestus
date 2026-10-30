import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";
import { withWidePage } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { buildFeedbackModeFixture, type FeedbackModeFixture, scaleFixture } from "./fixtures";
import { PracticeFeedbackModePage } from "./PracticeFeedbackModePage";

const from = ({ settings, rollup, groups, practices }: FeedbackModeFixture) => ({
	settings,
	tree: { status: "ready" as const, rollup, groups, practices },
});

const small = buildFeedbackModeFixture({
	groups: [
		{
			slug: "pull-request-hygiene",
			name: "Pull request hygiene",
			practices: [
				{
					name: "States the motivation",
					whyItMatters:
						"Your reviewer was not in your head when you wrote the code. A sentence on why you made the change spares them from reverse-engineering your intent from the diff.",
				},
				{ name: "Links the issue it closes", override: "APPROVE_FIRST" },
			],
		},
		{
			slug: "testing",
			name: "Testing",
			override: "OFF",
			practices: [{ name: "Covers the new branch" }, { name: "Names what it asserts" }],
		},
		{
			slug: null,
			name: null,
			practices: [{ name: "Handles the error state" }],
		},
	],
});

const chosenByTheWorkspace = buildFeedbackModeFixture({
	workspaceDefault: "APPROVE_FIRST",
	groups: [
		{
			slug: "pull-request-hygiene",
			name: "Pull request hygiene",
			practices: [{ name: "States the motivation" }, { name: "Links the issue it closes" }],
		},
	],
});

const oneDescribedAndOneBare = buildFeedbackModeFixture({
	groups: [
		{
			slug: "documentation",
			name: "Documentation",
			practices: [
				{
					name: "Explains the trade-off it chose",
					kind: "docs.document",
					whyItMatters:
						"A decision without its alternatives reads as arbitrary six months later. Naming what you did not do is what lets the next person tell a considered choice from an accident.",
				},
				{ name: "Written by hand, and says nothing more" },
			],
		},
	],
});

const oneDescribedPractice = buildFeedbackModeFixture({
	groups: [
		{
			slug: "documentation",
			name: "Documentation",
			practices: [
				{
					name: "Explains the trade-off it chose",
					kind: "docs.document",
					whyItMatters: "A decision without its alternatives reads as arbitrary six months later.",
				},
			],
		},
	],
});

const nothingSetByHand = buildFeedbackModeFixture({
	groups: [{ slug: "testing", name: "Testing", practices: [{ name: "Covers the new branch" }] }],
});

const notReviewedByHephaestus = buildFeedbackModeFixture({
	groups: [
		{
			slug: "observability",
			name: "Observability",
			practices: [
				{ name: "Alerts a human on failure", reviewedBy: "PEOPLE" },
				{ name: "Names the owner of each alert", reviewedBy: "NOBODY" },
			],
		},
	],
});

const offByGroup = buildFeedbackModeFixture({
	groups: [
		{
			slug: "testing",
			name: "Testing",
			override: "OFF",
			practices: [
				{ name: "Covers the new branch" },
				{ name: "Names what it asserts", override: "OFF" },
			],
		},
	],
});

/** The upgraded pilot: every practice carries its own Automatic, so neither default reaches one. */
const everyPracticeSetByHand = buildFeedbackModeFixture({
	workspaceDefault: "AUTOMATIC",
	groups: [
		{
			slug: "packaging",
			name: "Packaging work for review",
			practices: [
				{ name: "Keep the change reviewable", override: "AUTOMATIC" },
				{ name: "Write commit subjects that explain", override: "AUTOMATIC" },
			],
		},
	],
});

const noPractices = buildFeedbackModeFixture({ groups: [] });

const atScale = scaleFixture();

const idle = {
	workspace: false,
	groupSlugs: new Set<string>(),
	practiceSlugs: new Set<string>(),
	bulk: null,
};

const meta = {
	component: PracticeFeedbackModePage,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 1440] },
		viewport: { defaultViewport: "reflow" },
	},
	args: {
		workspaceSlug: "demo",
		...from(small),
		pending: idle,
		overridesOnly: false,
		onOverridesOnlyChange: fn(),
		onSetWorkspaceDefault: fn(),
		onClearWorkspaceDefault: fn(),
		onSetSending: fn(),
		onClearDeliverToMerged: fn(),
		onSetGroupFeedbackMode: fn(),
		onClearGroupFeedbackMode: fn(),
		onSetPracticeFeedbackMode: fn(),
		onClearPracticeFeedbackMode: fn(),
		onBulkSetFeedbackMode: fn(),
	},
	decorators: [withWidePage],
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={args.overridesOnly}>
			{(overridesOnly, setOverridesOnly) => (
				<PracticeFeedbackModePage
					{...args}
					overridesOnly={overridesOnly}
					onOverridesOnlyChange={(next) => {
						args.onOverridesOnlyChange(next);
						setOverridesOnly(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeFeedbackModePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WorkspaceDefaultUnset: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Workspace default" })).toBeVisible();
		await expect(canvas.getByText("Not chosen yet, so Approve first applies.")).toBeVisible();
		await expect(canvas.getByRole("radiogroup", { name: "Workspace feedback mode" })).toBeVisible();
		await expect(canvas.getByText(/It does not decide what Hephaestus reviews\./u)).toBeVisible();
		await expectNoPageOverflow();
	},
};

/** One tab answers when feedback reaches developers: the mode, then whether it is sent at all. */
export const SendingUnderTheWorkspaceDefault: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const headings = canvas.getAllByRole("heading", { level: 2 }).map((h) => h.textContent);
		await expect(headings.slice(0, 2)).toEqual(["Workspace default", "Sending feedback"]);
		await userEvent.click(canvas.getByRole("switch", { name: "Send feedback" }));
		await expect(args.onSetSending).toHaveBeenCalledWith({ deliveryStatus: "PAUSED" });
	},
};

export const WorkspaceDefaultSet: Story = {
	args: from(chosenByTheWorkspace),
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			within(canvas.getByRole("radiogroup", { name: "Workspace feedback mode" })).getByRole(
				"radio",
				{ name: "Approve first" },
			),
		).toBeChecked();

		await userEvent.click(
			canvas.getByRole("button", { name: "Use the default feedback mode for the workspace" }),
		);
		await expect(args.onClearWorkspaceDefault).toHaveBeenCalled();
	},
};

export const GroupSetByHand: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const testing = canvas.getByRole("radiogroup", {
			name: "Feedback mode for Testing",
		});
		await expect(within(testing).getByRole("radio", { name: "Off" })).toBeChecked();
		await userEvent.click(within(testing).getByRole("radio", { name: "Automatic" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(args.onSetGroupFeedbackMode).not.toHaveBeenCalled();
		await userEvent.click(dialog.getByRole("button", { name: "Send automatically" }));
		await expect(args.onSetGroupFeedbackMode).toHaveBeenCalledWith("testing", "AUTOMATIC");

		const unassigned = canvas.getByText("Unassigned").closest('[data-slot="accordion-item"]');
		if (!(unassigned instanceof HTMLElement)) {
			throw new Error("No-group group not rendered");
		}
		await expect(within(unassigned).getByText("Follows the workspace default")).toBeVisible();
		await expect(
			within(unassigned).queryByRole("radiogroup", { name: /Feedback mode for/u }),
		).not.toBeInTheDocument();
	},
};

export const WorkspaceAutomaticCanBeCancelled: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const workspace = canvas.getByRole("radiogroup", {
			name: "Workspace feedback mode",
		});
		await userEvent.click(within(workspace).getByRole("radio", { name: "Automatic" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
		await expect(args.onSetWorkspaceDefault).not.toHaveBeenCalled();
	},
};

export const PracticeAutomaticRequiresConfirmation: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Pull request hygiene/u }));
		const row = canvas.getByText("States the motivation").closest("li");
		if (!(row instanceof HTMLElement)) {
			throw new Error("Practice row not rendered");
		}
		await userEvent.click(within(row).getByRole("radio", { name: "Automatic" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(args.onSetPracticeFeedbackMode).not.toHaveBeenCalled();
		await userEvent.click(dialog.getByRole("button", { name: "Send automatically" }));
		await expect(args.onSetPracticeFeedbackMode).toHaveBeenCalledWith(
			"pull-request-hygiene-states-the-motivation",
			"AUTOMATIC",
		);
	},
};

export const PracticeSetByHandWithReset: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Pull request hygiene/u }));
		const row = canvas.getByText("Links the issue it closes").closest("li");
		if (!(row instanceof HTMLElement)) {
			throw new Error("Practice row not rendered");
		}

		await expect(within(row).getByText("Set here.")).toBeVisible();
		await expect(
			within(row).getByRole("radiogroup", {
				name: "Feedback mode for Links the issue it closes",
			}),
		).toBeVisible();

		const sibling = canvas.getByText("States the motivation").closest("li");
		if (!(sibling instanceof HTMLElement)) {
			throw new Error("Sibling row not rendered");
		}
		await expect(within(sibling).getByText(/^Follows/u)).toBeVisible();

		await userEvent.click(
			within(row).getByRole("button", {
				name: /Use the default feedback mode for Links the issue it closes/u,
			}),
		);
		await expect(args.onClearPracticeFeedbackMode).toHaveBeenCalledWith(
			"pull-request-hygiene-links-the-issue-it-closes",
		);
	},
};

export const PracticeContext: Story = {
	args: from(oneDescribedAndOneBare),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Documentation/u }));

		const described = canvas.getByText("Explains the trade-off it chose").closest("li");
		if (!(described instanceof HTMLElement)) {
			throw new Error("Practice row not rendered");
		}
		await expect(within(described).getByText("Document")).toBeVisible();
		await expect(
			within(described).queryByText(/reads as arbitrary six months later/u),
		).not.toBeInTheDocument();

		await userEvent.hover(
			within(described).getByRole("link", { name: "Explains the trade-off it chose" }),
		);
		await expectSettledVisible(await screen.findByText(/reads as arbitrary six months later/u));

		const bare = canvas.getByText("Written by hand, and says nothing more").closest("li");
		if (!(bare instanceof HTMLElement)) {
			throw new Error("Bare row not rendered");
		}
		await expect(within(bare).getByText("Pull or merge request")).toBeVisible();
		await expect(
			within(bare).getByRole("link", { name: "Written by hand, and says nothing more" }),
		).not.toHaveAttribute("data-slot", "hover-card-trigger");
		await expectNoPageOverflow();
	},
};

export const PracticeDetailOnKeyboardFocus: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: from(oneDescribedPractice),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Documentation/u }));
		const link = canvas.getByRole("link", { name: "Explains the trade-off it chose" });

		await expect(
			screen.queryByText(/reads as arbitrary six months later/u),
		).not.toBeInTheDocument();
		// The card opens on focus-visible; reach it by keyboard rather than programmatic focus.
		for (let step = 0; step < 12 && document.activeElement !== link; step += 1) {
			await userEvent.tab();
		}
		await expect(link).toHaveFocus();
		await expectSettledVisible(await screen.findByText(/reads as arbitrary six months later/u));
	},
};

export const SetByHandOnly: Story = {
	args: { overridesOnly: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Links the issue it closes")).toBeVisible();
		await expect(canvas.queryByText("States the motivation")).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: /^Testing/u })).toBeVisible();
		await expect(canvas.getByText("No practices here were set by hand")).toBeVisible();
		await expect(canvas.queryByText("Handles the error state")).not.toBeInTheDocument();
	},
};

export const SetByHandOnlyEmpty: Story = {
	args: { overridesOnly: true, ...from(nothingSetByHand) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing was set by hand")).toBeVisible();
	},
};

export const BulkSet: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Pull request hygiene/u }));
		await userEvent.click(
			canvas.getByRole("button", { name: /Select all 2 practices in Pull request hygiene/u }),
		);
		await expect(canvas.getByText("2 practices selected")).toBeVisible();

		await userEvent.click(canvas.getByRole("button", { name: "Change selected practices" }));
		const menu = within(await screen.findByRole("menu"));
		await userEvent.click(await menu.findByRole("menuitem", { name: "Approve first" }));
		await expect(args.onBulkSetFeedbackMode).toHaveBeenCalledWith(
			[
				"pull-request-hygiene-states-the-motivation",
				"pull-request-hygiene-links-the-issue-it-closes",
			],
			"APPROVE_FIRST",
		);
	},
};

export const BulkAutomaticRequiresConfirmation: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Pull request hygiene/u }));
		await userEvent.click(
			canvas.getByRole("button", { name: /Select all 2 practices in Pull request hygiene/u }),
		);
		await userEvent.click(canvas.getByRole("button", { name: "Change selected practices" }));
		await userEvent.click(
			within(await screen.findByRole("menu")).getByRole("menuitem", {
				name: "Automatic",
			}),
		);

		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(args.onBulkSetFeedbackMode).not.toHaveBeenCalled();

		await userEvent.click(dialog.getByRole("button", { name: "Send automatically" }));
		await expect(args.onBulkSetFeedbackMode).toHaveBeenCalledWith(
			[
				"pull-request-hygiene-states-the-motivation",
				"pull-request-hygiene-links-the-issue-it-closes",
			],
			"AUTOMATIC",
		);
	},
};

export const BulkClearToInherited: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Pull request hygiene/u }));
		await userEvent.click(
			canvas.getByRole("checkbox", { name: "Select Links the issue it closes" }),
		);
		await expect(canvas.getByText("1 practice selected")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Change selected practices" }));
		const menu = within(await screen.findByRole("menu"));
		await userEvent.click(
			await menu.findByRole("menuitem", { name: "Use the inherited feedback mode" }),
		);
		await expect(args.onBulkSetFeedbackMode).toHaveBeenCalledWith(
			["pull-request-hygiene-links-the-issue-it-closes"],
			null,
		);
	},
};

export const BulkInFlight: Story = {
	args: { pending: { ...idle, bulk: { done: 7, total: 24 } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Changing 7 of 24…")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Change selected practices" })).toBeDisabled();
	},
};

export const AtScale: Story = {
	args: from(atScale),
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				/^100 practices: 5 set to off, 90 set to approve first and 5 set to automatic\. 2 practices and 2 groups set by hand\.$/u,
			),
		).toBeVisible();
		await expect(canvas.getAllByRole("radiogroup")).toHaveLength(26);
		await expect(canvas.queryByRole("checkbox", { name: /^Select /u })).not.toBeInTheDocument();
		await expectNoPageOverflow();
	},
};

export const DecisionsShareOneColumn: Story = {
	args: from(atScale),
	globals: { viewport: { value: "desktop" } },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		// Assert the desktop breakpoint; a narrow layout would align every ladder regardless of the grid.
		await expect(window.innerWidth).toBeGreaterThanOrEqual(640);

		await userEvent.click(canvas.getByRole("button", { name: /^Pull request hygiene/u }));

		const lefts = canvas
			.getAllByRole("radiogroup", { name: /^Feedback mode for /u })
			.map((group) => Math.round(group.getBoundingClientRect().left));

		await expect(lefts.length).toBeGreaterThan(25);
		await expect(Math.max(...lefts) - Math.min(...lefts)).toBeLessThanOrEqual(2);
	},
};

/**
 * A group whose practices are all set by hand: its own setting reaches none of them, and the page
 * says so beside it with the way back. At desktop width the three mode names fit whole.
 */
export const EveryPracticeSetByHand: Story = {
	args: from(everyPracticeSetByHand),
	globals: { viewport: { value: "desktop" } },
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.queryByText("Follows the workspace default")).not.toBeInTheDocument();
		await expect(
			canvas.getByText(/All 2 practices are set by hand, so this group setting has no effect\./u),
		).toBeVisible();

		const ladder = canvas.getByRole("radiogroup", {
			name: "Feedback mode for Packaging work for review",
		});
		for (const name of ["Off", "Approve first", "Automatic"]) {
			const label = within(ladder).getByText(name);
			await expect(label.scrollWidth).toBeLessThanOrEqual(label.clientWidth);
		}

		await userEvent.click(
			canvas.getByRole("button", {
				name: "Reset the practices in Packaging work for review to follow this group setting",
			}),
		);
		await expect(args.onBulkSetFeedbackMode).toHaveBeenCalledWith(
			everyPracticeSetByHand.practices.map((practice) => practice.slug),
			null,
		);
	},
};

export const AtScaleSetByHandOnly: Story = {
	args: { overridesOnly: true, ...from(atScale) },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("checkbox", { name: /^Select /u })).toHaveLength(2);
		await expect(canvas.getByText(/^Security: links the issue it closes/u)).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const OffDirectAndInherited: Story = {
	args: from(offByGroup),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^Testing\b/u }));
		const off =
			"Off keeps observations and stops feedback on the work, on practice pages, and in conversation.";
		const inherited = canvas.getByText("Covers the new branch").closest("li");
		const direct = canvas.getByText("Names what it asserts").closest("li");
		if (!(inherited instanceof HTMLElement) || !(direct instanceof HTMLElement)) {
			throw new Error("Practice rows not rendered");
		}
		await expect(within(inherited).getByText("Follows Testing")).toBeVisible();
		await expect(within(inherited).getByText(off)).toBeVisible();
		await expect(within(direct).getByText("Set here.")).toBeVisible();
		await expect(within(direct).getByText(off)).toBeVisible();
	},
};

export const NotReviewedByHephaestus: Story = {
	args: from(notReviewedByHephaestus),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Observability/u }));
		await expect(
			canvas.getByText(/^Reviewed by: People\. Only the team knows which incidents/u),
		).toBeVisible();
		await expect(
			canvas.getByText(
				/^Reviewed by: Nobody\. This practice offers guidance without automated review\./u,
			),
		).toBeVisible();
		await expect(
			canvas.getAllByText(
				/Hephaestus does not review it, which is separate from feedback mode Off\./u,
			),
		).toHaveLength(2);
		await expect(
			canvas.getByRole("radiogroup", { name: "Feedback mode for Alerts a human on failure" }),
		).toBeVisible();
	},
};

export const PracticesLoading: Story = {
	args: { tree: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radiogroup", { name: "Workspace feedback mode" })).toBeVisible();
		await expect(canvas.getByRole("switch", { name: "Send feedback" })).toBeVisible();
		await expect(
			canvas.queryByRole("toolbar", { name: "Filter practices" }),
		).not.toBeInTheDocument();
	},
};

const retryPractices = fn();

/** A failed practice list keeps the workspace settings: pause and resume never wait on it. */
export const PracticesLoadFailed: Story = {
	args: { tree: { status: "error", error: { status: 503 }, onRetry: retryPractices } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load the practices")).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: "Send feedback" }));
		await expect(args.onSetSending).toHaveBeenCalledWith({ deliveryStatus: "PAUSED" });
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retryPractices).toHaveBeenCalled();
	},
};

export const Empty: Story = {
	args: from(noPractices),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practices yet")).toBeVisible();
		await expect(canvas.getByText("No practices yet.")).toBeVisible();
		// Sending is a workspace setting, so it stays reachable before any practice exists.
		await expect(canvas.getByRole("switch", { name: "Send feedback" })).toBeVisible();
	},
};
