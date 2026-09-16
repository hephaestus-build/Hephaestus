import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";
import { withWidePage } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { type AutonomyFixture, buildAutonomyFixture, scaleFixture } from "./fixtures";
import { PracticeAutonomyPage } from "./PracticeAutonomyPage";

const from = ({ settings, rollup, practices }: AutonomyFixture) => ({
	settings,
	rollup,
	practices,
});

const small = buildAutonomyFixture({
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
				{ name: "Links the issue it closes", override: "HUMAN_APPROVAL" },
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

const chosenByTheWorkspace = buildAutonomyFixture({
	workspaceDefault: "HUMAN_APPROVAL",
	groups: [
		{
			slug: "pull-request-hygiene",
			name: "Pull request hygiene",
			practices: [{ name: "States the motivation" }, { name: "Links the issue it closes" }],
		},
	],
});

const oneDescribedAndOneBare = buildAutonomyFixture({
	groups: [
		{
			slug: "documentation",
			name: "Documentation",
			practices: [
				{
					name: "Explains the trade-off it chose",
					artifactKind: "docs.document",
					whyItMatters:
						"A decision without its alternatives reads as arbitrary six months later. Naming what you did not do is what lets the next person tell a considered choice from an accident.",
				},
				{ name: "Written by hand, and says nothing more" },
			],
		},
	],
});

const oneDescribedPractice = buildAutonomyFixture({
	groups: [
		{
			slug: "documentation",
			name: "Documentation",
			practices: [
				{
					name: "Explains the trade-off it chose",
					artifactKind: "docs.document",
					whyItMatters: "A decision without its alternatives reads as arbitrary six months later.",
				},
			],
		},
	],
});

const nothingSetByHand = buildAutonomyFixture({
	groups: [{ slug: "testing", name: "Testing", practices: [{ name: "Covers the new branch" }] }],
});

const oneUnreviewablePractice = buildAutonomyFixture({
	groups: [
		{
			slug: "observability",
			name: "Observability",
			practices: [{ name: "Alerts a human on failure", reviewable: false }],
		},
	],
});

/** Built once and shared: resolving the whole inheritance chain over this many rows is not free. */
const atScale = scaleFixture();

const idle = {
	workspace: false,
	groupSlugs: new Set<string>(),
	practiceSlugs: new Set<string>(),
	bulk: null,
};

const meta = {
	component: PracticeAutonomyPage,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 1440] },
		viewport: { defaultViewport: "reflow" },
	},
	args: {
		workspaceSlug: "demo",
		settings: small.settings,
		rollup: small.rollup,
		practices: small.practices,
		pending: idle,
		overridesOnly: false,
		onOverridesOnlyChange: fn(),
		onSetWorkspaceDefault: fn(),
		onClearWorkspaceDefault: fn(),
		onSetGroupAutonomy: fn(),
		onClearGroupAutonomy: fn(),
		onSetPracticeAutonomy: fn(),
		onClearPracticeAutonomy: fn(),
		onBulkSetAutonomy: fn(),
	},
	decorators: [withWidePage],
	tags: ["autodocs"],
	/**
	 * Only the scope filter is held in state; the autonomy setters stay spies. {@link buildAutonomyFixture}
	 * resolves the three-level inheritance chain the way the server does, so applying an autonomy locally
	 * would mean resolving it a second time by hand — and the page would show a rollup its own rows
	 * contradict the first time the two disagreed.
	 */
	render: (args) => (
		<Stateful initial={args.overridesOnly}>
			{(overridesOnly, setOverridesOnly) => (
				<PracticeAutonomyPage
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
} satisfies Meta<typeof PracticeAutonomyPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WorkspaceDefaultUnset: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Workspace default" })).toBeVisible();
		await expect(
			canvas.getByText("Not chosen yet, so Review before sending applies."),
		).toBeVisible();
		await expect(
			canvas.getByRole("radiogroup", { name: "How far reviews go without you" }),
		).toBeVisible();
		// The workspace makes one decision, not two: how far a review goes is the only axis, and
		// Review before sending means a person decides whether the composed feedback is released.
		await expect(canvas.queryByText(/feedback may go/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/mentor conversation/iu)).not.toBeInTheDocument();
		await expectNoPageOverflow();
	},
};

export const WorkspaceDefaultSet: Story = {
	args: from(chosenByTheWorkspace),
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			within(canvas.getByRole("radiogroup", { name: "How far reviews go without you" })).getByRole(
				"radio",
				{ name: "Review before sending" },
			),
		).toBeChecked();

		await userEvent.click(
			canvas.getByRole("button", { name: "Use the default for how far reviews go without you" }),
		);
		await expect(args.onClearWorkspaceDefault).toHaveBeenCalled();
	},
};

export const GroupOverride: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const testing = canvas.getByRole("radiogroup", {
			name: "How far reviews go in Testing",
		});
		await expect(within(testing).getByRole("radio", { name: "Off" })).toBeChecked();
		await userEvent.click(within(testing).getByRole("radio", { name: "Send automatically" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(args.onSetGroupAutonomy).not.toHaveBeenCalled();
		await userEvent.click(dialog.getByRole("button", { name: "Start sending automatically" }));
		await expect(args.onSetGroupAutonomy).toHaveBeenCalledWith("testing", "AUTOMATIC");

		const unassigned = canvas.getByText("Unassigned").closest('[data-slot="accordion-item"]');
		if (!(unassigned instanceof HTMLElement)) {
			throw new Error("No-group group not rendered");
		}
		await expect(within(unassigned).getByText("Follows the workspace default")).toBeVisible();
		await expect(
			within(unassigned).queryByRole("radiogroup", { name: /How far reviews go in/u }),
		).not.toBeInTheDocument();
	},
};

export const WorkspaceAutomaticCanBeCancelled: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const workspace = canvas.getByRole("radiogroup", {
			name: "How far reviews go without you",
		});
		await userEvent.click(within(workspace).getByRole("radio", { name: "Send automatically" }));
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
		await userEvent.click(within(row).getByRole("radio", { name: "Send automatically" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(args.onSetPracticeAutonomy).not.toHaveBeenCalled();
		await userEvent.click(dialog.getByRole("button", { name: "Start sending automatically" }));
		await expect(args.onSetPracticeAutonomy).toHaveBeenCalledWith(
			"pull-request-hygiene-states-the-motivation",
			"AUTOMATIC",
		);
	},
};

export const PracticeOverrideWithReset: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Pull request hygiene/u }));
		const row = canvas.getByText("Links the issue it closes").closest("li");
		if (!(row instanceof HTMLElement)) {
			throw new Error("Practice row not rendered");
		}

		// `getByText` matches an element's own text nodes, so this is the paragraph and not the button
		// inside it; the button is asserted by role below.
		await expect(within(row).getByText("Set here.")).toBeVisible();
		await expect(
			within(row).getByRole("radiogroup", {
				name: "How far reviews go on Links the issue it closes",
			}),
		).toBeVisible();

		const sibling = canvas.getByText("States the motivation").closest("li");
		if (!(sibling instanceof HTMLElement)) {
			throw new Error("Sibling row not rendered");
		}
		await expect(within(sibling).getByText(/^Follows/u)).toBeVisible();

		await userEvent.click(
			within(row).getByRole("button", { name: /Use the default for Links the issue it closes/u }),
		);
		await expect(args.onClearPracticeAutonomy).toHaveBeenCalledWith(
			"pull-request-hygiene-links-the-issue-it-closes",
		);
	},
};

/**
 * A practice carrying no prose gets no preview card at all, rather than an empty popup appearing
 * under the pointer. Locally written practices usually carry none, so this is a state production
 * reaches routinely.
 */
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

		// The card is portalled, so it is found on the screen and not in the row.
		await userEvent.hover(
			within(described).getByRole("link", { name: "Explains the trade-off it chose" }),
		);
		await expectSettledVisible(await screen.findByText(/reads as arbitrary six months later/u));

		const bare = canvas.getByText("Written by hand, and says nothing more").closest("li");
		if (!(bare instanceof HTMLElement)) {
			throw new Error("Bare row not rendered");
		}
		await expect(within(bare).getByText("Pull or merge request")).toBeVisible();
		// A practice with neither field renders its link bare rather than wrapped in a card that
		// would open on hover with nothing in it. Asserted on the element, not by hovering and
		// waiting for nothing, which passes just as well when the card is merely slow.
		await expect(
			within(bare).getByRole("link", { name: "Written by hand, and says nothing more" }),
		).not.toHaveAttribute("data-slot", "hover-card-trigger");
		await expectNoPageOverflow();
	},
};

/**
 * The card hangs off the practice's own link rather than a help icon, so the tab stop the row already
 * has is the keyboard path and no row grows a second one. This story is what keeps that a fact rather
 * than a claim: Base UI's hover card opens on focus-visible, Radix's does not.
 */
export const PracticeDetailOnKeyboardFocus: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: from(oneDescribedPractice),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Documentation/u }));
		const link = canvas.getByRole("link", { name: "Explains the trade-off it chose" });

		await expect(
			screen.queryByText(/reads as arbitrary six months later/u),
		).not.toBeInTheDocument();
		// `link.focus()` would not do: the card opens on focus-*visible*, so focus has to arrive by
		// keyboard. Bounded, so a DOM change ahead of the link fails the story instead of hanging it.
		for (let step = 0; step < 12 && document.activeElement !== link; step++) {
			await userEvent.tab();
		}
		await expect(link).toHaveFocus();
		await expectSettledVisible(await screen.findByText(/reads as arbitrary six months later/u));
	},
};

export const OverridesOnly: Story = {
	args: { overridesOnly: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Links the issue it closes")).toBeVisible();
		await expect(canvas.queryByText("States the motivation")).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: /^Testing/u })).toBeVisible();
		await expect(canvas.getByText("No practices here were set by hand.")).toBeVisible();
		await expect(canvas.queryByText("Handles the error state")).not.toBeInTheDocument();
	},
};

export const OverridesOnlyEmpty: Story = {
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

		await userEvent.click(canvas.getByRole("button", { name: "Change the selected" }));
		const menu = within(await screen.findByRole("menu"));
		await userEvent.click(await menu.findByRole("menuitem", { name: "Review before sending" }));
		await expect(args.onBulkSetAutonomy).toHaveBeenCalledWith(
			[
				"pull-request-hygiene-states-the-motivation",
				"pull-request-hygiene-links-the-issue-it-closes",
			],
			"HUMAN_APPROVAL",
		);
	},
};

export const BulkAutomaticRequiresConfirmation: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Pull request hygiene/u }));
		await userEvent.click(
			canvas.getByRole("button", { name: /Select all 2 practices in Pull request hygiene/u }),
		);
		await userEvent.click(canvas.getByRole("button", { name: "Change the selected" }));
		await userEvent.click(
			within(await screen.findByRole("menu")).getByRole("menuitem", {
				name: "Send automatically",
			}),
		);

		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(args.onBulkSetAutonomy).not.toHaveBeenCalled();

		await userEvent.click(dialog.getByRole("button", { name: "Start sending automatically" }));
		await expect(args.onBulkSetAutonomy).toHaveBeenCalledWith(
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
		await userEvent.click(canvas.getByRole("button", { name: "Change the selected" }));
		const menu = within(await screen.findByRole("menu"));
		await userEvent.click(await menu.findByRole("menuitem", { name: "Use the inherited setting" }));
		await expect(args.onBulkSetAutonomy).toHaveBeenCalledWith(
			["pull-request-hygiene-links-the-issue-it-closes"],
			null,
		);
	},
};

export const BulkInFlight: Story = {
	args: { pending: { ...idle, bulk: { done: 7, total: 24 } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Changing 7 of 24…")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Change the selected" })).toBeDisabled();
	},
};

export const AtScale: Story = {
	args: from(atScale),
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				/^100 practices: 6 off, 89 review before sending and 5 send automatically\. \d+ practices and \d+ groups set by hand\.$/u,
			),
		).toBeVisible();
		await expect(canvas.getAllByRole("radiogroup")).toHaveLength(26);
		await expect(canvas.queryByRole("checkbox", { name: /^Select /u })).not.toBeInTheDocument();
		await expectNoPageOverflow();
	},
};

/**
 * A geometry assertion, because a ladder laid out after a content-width header takes its left edge
 * from the length of the group's name — and nothing about the DOM, the roles or the text changes when
 * that happens, so no other kind of assertion catches it.
 */
export const DecisionsShareOneColumn: Story = {
	args: from(atScale),
	globals: { viewport: { value: "desktop" } },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		// Below `sm` every ladder is full width and shares a left edge regardless, so a runner that
		// ignored the viewport would pass this story for the wrong reason.
		await expect(window.innerWidth).toBeGreaterThanOrEqual(640);

		await userEvent.click(canvas.getByRole("button", { name: /^Pull request hygiene/u }));

		const lefts = canvas
			.getAllByRole("radiogroup", { name: /^How far reviews go (?:in|on) /u })
			.map((group) => Math.round(group.getBoundingClientRect().left));

		await expect(lefts.length).toBeGreaterThan(25);
		// One column, to within the sub-pixel rounding of a grid track inside a grid track.
		await expect(Math.max(...lefts) - Math.min(...lefts)).toBeLessThanOrEqual(2);
	},
};

export const AtScaleOverridesOnly: Story = {
	args: { overridesOnly: true, ...from(atScale) },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("checkbox", { name: /^Select /u })).toHaveLength(3);
		await expect(canvas.getByText(/^Observability: keeps the change/u)).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const NotReviewable: Story = {
	args: from(oneUnreviewablePractice),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Observability/u }));
		await expect(
			canvas.getByText("This practice can't be reviewed automatically, so it stays off."),
		).toBeVisible();
		await expect(canvas.getByRole("checkbox", { name: /^Select /u })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};
