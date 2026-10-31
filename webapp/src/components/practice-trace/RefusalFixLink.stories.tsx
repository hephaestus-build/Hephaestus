import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { RefusalFixLink, type RefusalFixLinkProps } from "./RefusalFixLink";
import { REFUSAL_FIXES, type RefusalReason } from "./trace-format";

/** Keyed on the wire union, so a reason the server adds is a compile error here until it is listed. */
const REASON_KEYS = {
	GATE_WITHHELD: true,
	COOLDOWN_ACTIVE: true,
	REQUEST_COOLDOWN_ACTIVE: true,
	REQUESTER_QUOTA_EXHAUSTED: true,
	CONCURRENT_DUPLICATE: true,
	COALESCED: true,
	OUT_OF_REVIEW_SCOPE: true,
	BOT_AUTHOR: true,
	BOT_REVIEWER: true,
	STALE_ROLLOUT_REVISION: true,
	WORKSPACE_INACTIVE: true,
	PRACTICES_DISABLED: true,
	NO_ACTIVE_PRACTICE: true,
	REVIEW_MODEL_UNBOUND: true,
	MEMBER_AI_DECLINED: true,
	PERSON_DATA_ERASED: true,
	FEEDBACK_MODE_OFF: true,
	SUBJECT_UNLINKED: true,
	MERGE_ACTOR_UNAVAILABLE: true,
	MODEL_UNAVAILABLE: true,
	BUDGET_EXHAUSTED: true,
	ITEM_NOT_VISIBLE: true,
	VERSION_SUPERSEDED: true,
	PENDING_DEADLINE_EXCEEDED: true,
	ITEM_GONE: true,
	LEGACY_REVIEW_UNBOUND: true,
	OCCASION_EVIDENCE_UNAVAILABLE: true,
	QUIET_WINDOW: true,
	REVIEW_IN_PROGRESS: true,
	ALREADY_CURRENT: true,
} satisfies Record<RefusalReason, true>;

const REASONS = Object.keys(REASON_KEYS).filter((key): key is RefusalReason => key in REASON_KEYS);

/**
 * Written out rather than derived from `REFUSAL_FIXES`: branching on `section` the way the component
 * does would make a wrong component and a wrong test agree. `how-much` is the Review page's default
 * section, so it carries no search param.
 */
const EXPECTED_HREFS: readonly (readonly [RefusalReason, string])[] = [
	["GATE_WITHHELD", "/w/demo/admin/practices/review?section=automated-review"],
	["OUT_OF_REVIEW_SCOPE", "/w/demo/admin/practices/review?section=automated-review"],
	["PRACTICES_DISABLED", "/w/demo/admin/practices/review?section=automated-review"],
	["FEEDBACK_MODE_OFF", "/w/demo/admin/practices/review"],
	["NO_ACTIVE_PRACTICE", "/w/demo/admin/practices"],
	["REVIEW_MODEL_UNBOUND", "/w/demo/admin/models"],
	["MODEL_UNAVAILABLE", "/w/demo/admin/models"],
	["BUDGET_EXHAUSTED", "/w/demo/admin/usage"],
];

/**
 * The whole refusal vocabulary at once, each row named by its reason code: the sentence a reader sees
 * beside the link comes from the shared vocabulary. Takes the
 * component's own props and overrides only `reason`, so the Controls panel still drives every other
 * input.
 */
function RefusalCatalogue(props: RefusalFixLinkProps) {
	return (
		<ul className="max-w-2xl space-y-2 text-sm">
			{REASONS.map((reason) => (
				<li key={reason} aria-label={reason} className="flex flex-wrap items-baseline gap-x-1.5">
					<code className="text-xs text-muted-foreground">{reason}</code>
					<RefusalFixLink {...props} reason={reason} />
				</li>
			))}
		</ul>
	);
}

const meta = {
	component: RefusalFixLink,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: { workspaceSlug: "demo", reason: "REVIEW_MODEL_UNBOUND" },
} satisfies Meta<typeof RefusalFixLink>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Set up a review model" })).toHaveAttribute(
			"href",
			"/w/demo/admin/models",
		);
	},
};

/** The second shape a fix has: one route plus a search param rather than a route of its own. */
export const ASectionOfTheReviewPage: Story = {
	args: { reason: "GATE_WITHHELD" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: "Open Review: Automated review" }),
		).toHaveAttribute("href", "/w/demo/admin/practices/review?section=automated-review");
	},
};

/** A cooldown expires on its own, so there is nothing to send an admin to. */
export const NoFixForThisReason: Story = {
	args: { reason: "COOLDOWN_ACTIVE" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

/** Every reason an admin can act on, and every one they cannot: the gaps are as deliberate. */
export const EveryReason: Story = {
	render: (args) => <RefusalCatalogue {...args} />,
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(REASONS.length);

		const links = canvas.getAllByRole("link");
		await expect(links).toHaveLength(EXPECTED_HREFS.length);
		// A link is read out of its sentence, so its name has to name the destination (WCAG 2.4.4).
		for (const link of links) {
			await expect(link).toHaveAccessibleName(/^(?:Open|Set up) \S/u);
		}
	},
};

/** Several reasons reach one screen, which is why a label names its destination, not its reason. */
export const WhereEachFixLives: Story = {
	render: (args) => <RefusalCatalogue {...args} />,
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		// A reason that grows a fix without an entry above fails here rather than going unchecked.
		await expect(EXPECTED_HREFS.map(([reason]) => reason).sort()).toEqual(
			Object.keys(REFUSAL_FIXES).sort(),
		);

		for (const [reason, href] of EXPECTED_HREFS) {
			const row = canvas.getByRole("listitem", { name: reason });
			await expect(within(row).getByRole("link")).toHaveAttribute("href", href);
		}
	},
};
