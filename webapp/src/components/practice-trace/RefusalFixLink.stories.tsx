import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { RefusalFixLink, type RefusalFixLinkProps } from "./RefusalFixLink";
import { REFUSAL_FIXES, SIGNAL_STATE_REASON_LABELS, type SignalStateReason } from "./trace-format";

const REASONS: SignalStateReason[] = [
	"GATE_SKIPPED",
	"COOLDOWN_ACTIVE",
	"REQUEST_COOLDOWN_ACTIVE",
	"REQUESTER_QUOTA_EXHAUSTED",
	"CONCURRENT_DUPLICATE",
	"COALESCED",
	"OUT_OF_REVIEW_SCOPE",
	"STALE_ROLLOUT_REVISION",
	"WORKSPACE_INACTIVE",
	"PRACTICES_DISABLED",
	"NO_ACTIVE_PRACTICE",
	"REVIEW_MODEL_UNBOUND",
	"PRACTICE_AUTONOMY_OFF",
	"BUDGET_EXHAUSTED",
	"SUBJECT_UNLINKED",
	"MEMBER_AI_DECLINED",
	"MODEL_UNAVAILABLE",
	"ARTIFACT_NOT_VISIBLE",
	"PENDING_DEADLINE_EXCEEDED",
	"ARTIFACT_GONE",
];

/**
 * Written out rather than derived from `REFUSAL_FIXES`: branching on `section` the way the component
 * does would make a wrong component and a wrong test agree. `how-much` is the Review page's default
 * section, so it carries no search param.
 */
const EXPECTED_HREFS: ReadonlyArray<readonly [SignalStateReason, string]> = [
	["GATE_SKIPPED", "/w/demo/admin/practices/review?section=when-and-where"],
	["OUT_OF_REVIEW_SCOPE", "/w/demo/admin/practices/review?section=when-and-where"],
	["PRACTICES_DISABLED", "/w/demo/admin/practices/review?section=when-and-where"],
	["PRACTICE_AUTONOMY_OFF", "/w/demo/admin/practices/review"],
	["NO_ACTIVE_PRACTICE", "/w/demo/admin/practices"],
	["REVIEW_MODEL_UNBOUND", "/w/demo/admin/models"],
	["MODEL_UNAVAILABLE", "/w/demo/admin/models"],
	["BUDGET_EXHAUSTED", "/w/demo/admin/usage"],
];

/**
 * The whole refusal vocabulary at once. Takes the component's own props and overrides only
 * `reason`, so the Controls panel still drives every other input.
 */
function RefusalCatalogue(props: RefusalFixLinkProps) {
	return (
		<ul className="max-w-2xl space-y-2 text-sm">
			{REASONS.map((reason) => (
				<li key={reason} className="flex flex-wrap items-baseline gap-x-1.5">
					<span className="text-muted-foreground">{SIGNAL_STATE_REASON_LABELS[reason]}.</span>
					<RefusalFixLink {...props} reason={reason} />
				</li>
			))}
		</ul>
	);
}

const meta = {
	title: "Practice trace/Refusal fix link",
	component: RefusalFixLink,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: { workspaceSlug: "demo", reason: "REVIEW_MODEL_UNBOUND", canAdminister: true },
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
	args: { reason: "GATE_SKIPPED" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Open Review: When and where" })).toHaveAttribute(
			"href",
			"/w/demo/admin/practices/review?section=when-and-where",
		);
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
		await expect([...REASONS].sort()).toEqual(Object.keys(SIGNAL_STATE_REASON_LABELS).sort());

		const links = canvas.getAllByRole("link");
		await expect(links).toHaveLength(EXPECTED_HREFS.length);
		// A link is read out of its sentence, so its name has to name the destination (WCAG 2.4.4).
		for (const link of links) {
			await expect(link).toHaveAccessibleName(/^(Open|Set up) \S/);
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
			const sentence = SIGNAL_STATE_REASON_LABELS[reason];
			const row = canvas.getByText(`${sentence}.`).closest("li");
			if (!(row instanceof HTMLElement)) throw new Error(`No row for ${reason}`);
			await expect(within(row).getByRole("link")).toHaveAttribute("href", href);
		}
	},
};

export const AMemberSeesNoLinks: Story = {
	args: { canAdminister: false },
	render: (args) => <RefusalCatalogue {...args} />,
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No AI model is set up to run reviews.")).toBeVisible();
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};
