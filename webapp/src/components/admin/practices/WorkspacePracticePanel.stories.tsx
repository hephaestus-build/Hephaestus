import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { PracticePrecomputeSummary } from "@/api/types.gen";
import { mockPractices, precomputeScript } from "@/components/admin/practices/fixtures";
import { PURPOSE_STATUS_DEFS } from "@/components/practice-vocabulary/purpose-status-defs";
import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";
import { precedes } from "@/test/dom";

import {
	type PrecomputeNeedsState,
	WorkspacePracticePanel,
	type WorkspacePracticeState,
} from "./WorkspacePracticePanel";

const [practice] = mockPractices;
if (!practice) {
	throw new Error("The shared practice fixtures no longer hold a practice to show");
}

type ReadyState = Extract<WorkspacePracticeState, { status: "ready" }>;

const ready = (over: Partial<ReadyState> = {}): ReadyState => ({
	status: "ready",
	practice,
	definitionOptions: mockPracticeDefinitionOptions,
	groupName: "Review-ready work",
	...over,
});

/** A practice whose script declares these models, the way the precompute guide teaches. */
const scriptedWith = (models: Parameters<typeof precomputeScript>[0]) =>
	ready({ practice: { ...practice, precomputeScript: precomputeScript(models) } });

const scripted = scriptedWith({ decision: "required" });

const REVIEW_JOB_ID = "6f1c2b7a-0d3e-4c55-9a51-2f7a3e8b9c10";

const needs = (over: Partial<PracticePrecomputeSummary> = {}): PrecomputeNeedsState => ({
	status: "ready",
	summary: {
		practiceSlug: practice.slug,
		practiceName: practice.name,
		asOf: { jobId: REVIEW_JOB_ID, finishedAt: daysBefore(7) },
		scriptChanged: false,
		needs: [],
		...over,
	},
});

/** The text colour of a status tone on this page, read from a probe that leaves no trace. */
function toneColour(tone: string): string {
	const probe = document.createElement("span");
	probe.className = `text-${tone}`;
	document.body.append(probe);
	const { color } = getComputedStyle(probe);
	probe.remove();
	return color;
}

const meta = {
	component: WorkspacePracticePanel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		workspaceSlug: "demo",
		state: ready(),
		precompute: { status: "ready", summary: undefined },
		path: { behind: [{ label: "Practice setup", depth: 0 }], onClose: fn() },
	},
	argTypes: {
		state: { control: false },
		precompute: { control: false },
		path: { control: false },
	},
	render: (args) => (
		<InLevelStack entry={{ kind: "practice", id: practice.slug }} path={args.path}>
			{(level) => <WorkspacePracticePanel {...args} {...level} />}
		</InLevelStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof WorkspacePracticePanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args }) => {
		const edit = await screen.findByRole("link", { name: "Edit practice" });
		await expectSettledVisible(edit);
		// Editing is another level on top of this one, so leaving the editor lands back on the panel
		// it was opened from rather than on the bare tree.
		await expect(levelsOpenedBy(edit).at(-1)).toBe(`practice-edit:${practice.slug}`);
		// Level 2 is the panel's own title; criteria headings render below it at level 4.
		await expect(screen.getByRole("heading", { name: practice.name, level: 2 })).toBeVisible();
		// The path says where the level sits, and its crumb closes back down to the page.
		const path = screen.getByRole("list", { name: "Path" });
		await expect(path).toHaveTextContent(/Practice setup.*Practice/u);
		await userEvent.click(within(path).getByRole("button", { name: "Practice setup" }));
		await expect(args.path.onClose).toHaveBeenCalledWith(0);
	},
};

export const FromTheCatalog: Story = {
	args: {
		state: ready({
			practice: {
				...practice,
				catalogOrigin: { slug: practice.slug, link: "IN_SYNC", sourceOffered: true },
			},
		}),
	},
	play: async () => {
		// What the badge in the catalog list can only label is spelled out here, in the page it opens.
		await expectSettledVisible(await screen.findByText(/will not edit your copy/u));
	},
};

export const InheritedAutonomy: Story = {
	args: {
		state: ready({
			practice: {
				...practice,
				autonomy: {
					effective: "HUMAN_APPROVAL",
					inherited: true,
					source: "GROUP",
				},
			},
		}),
	},
	play: async () => {
		// An inherited value says where it came from, so the reader knows where to go to change it.
		await expectSettledVisible(await screen.findByText(/Follows Review-ready work/u));
	},
};

export const Unassigned: Story = {
	args: {
		state: ready({ practice: { ...practice, groupSlug: undefined }, groupName: undefined }),
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
		// The drawer is named by what is loading while its title's shape stands in.
		await expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("Loading practice");
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

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};

/** Opens the script's disclosure, where what it needs sits above its source. */
async function openScript() {
	const trigger = await screen.findByRole("button", { name: "Precompute script" });
	await expectSettledVisible(trigger);
	await userEvent.click(trigger);
	return trigger;
}

/** A practice without a precompute script has nothing to say about models, so nothing names one. */
export const PrecomputeNoScript: Story = {
	args: { precompute: needs() },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("link", { name: "Edit practice" }));
		await expect(screen.queryByText(/precompute script/iu)).not.toBeInTheDocument();
		await expect(screen.queryByRole("note")).not.toBeInTheDocument();
	},
};

export const PrecomputeUsesNoModel: Story = {
	args: { state: scriptedWith({}), precompute: needs() },
	play: async () => {
		await openScript();
		await expectSettledVisible(await screen.findByText("It uses no model."));
		// The needs speak for one review, and the reader can open it.
		const asOf = screen.getByRole("link", { name: /^As of the review on \d{1,2} \w{3}$/u });
		await expect(asOf.getAttribute("href")).toMatch(/^\/w\/demo\/admin\/practices\/reviews\?/u);
		await expect(levelsOpenedBy(asOf)).toStrictEqual([`review:${REVIEW_JOB_ID}`]);
	},
};

/** An optional model with an assignment for every tier is the quiet case: no badge, no alert. */
export const PrecomputeOptionalMet: Story = {
	args: {
		state: scriptedWith({ embedding: "optional" }),
		precompute: needs({
			needs: [{ purpose: "PRACTICE_EMBEDDING", need: "OPTIONAL", unmetTiers: [] }],
		}),
	},
	play: async () => {
		await openScript();
		const models = screen.getByRole("list", { name: "Models the script uses" });
		await expectSettledVisible(within(models).getByText("Embedding model"));
		await expect(within(models).queryByText("Required")).not.toBeInTheDocument();
		await expect(screen.queryByText(/^Not set for/u)).not.toBeInTheDocument();
		await expect(screen.queryByRole("link", { name: /^Assign /u })).not.toBeInTheDocument();
		await expect(screen.queryByRole("note")).not.toBeInTheDocument();
	},
};

/**
 * Only a required model that some members have no assignment for stops the script, so only that
 * earns the alert at the top. Each need row names the members no model serves, in the tiers' own
 * words; members who have not made an AI choice are named as such, not by the "Not declared" label.
 * The review model always runs the review, so it has no row.
 */
export const PrecomputeRequiredUnmet: Story = {
	args: {
		state: scriptedWith({ chat: "required", decision: "required", reranking: "optional" }),
		precompute: needs({
			needs: [
				{ purpose: "PRACTICE_DECISION", need: "REQUIRED", unmetTiers: ["IN_HOUSE"] },
				{ purpose: "PRACTICE_REVIEW", need: "REQUIRED", unmetTiers: [] },
				{
					purpose: "PRACTICE_RERANKING",
					need: "OPTIONAL",
					unmetTiers: ["CLOUD", "UNDECLARED"],
				},
			],
		}),
	},
	play: async () => {
		const alert = await screen.findByRole("note");
		await expectSettledVisible(alert);
		await expect(alert).toHaveTextContent(
			"The precompute script does not run for In-house members’ work: no decision model is assigned for them. Assign a decision model",
		);
		await expect(
			within(alert).getByRole("link", { name: "Assign a decision model" }),
		).toHaveAttribute("href", "/w/demo/admin/models?purpose=PRACTICE_DECISION");
		// One state, one tone: the alert wears the colour of the model row's *Needs attention* badge.
		await expect(getComputedStyle(alert).color).toBe(
			toneColour(PURPOSE_STATUS_DEFS.NEEDS_ATTENTION.badgeVariant),
		);
		// The alert leads the panel: it comes before why the practice matters.
		await expect(precedes(alert, screen.getByText(/^A clear description lets reviewers/u))).toBe(
			true,
		);

		await openScript();
		const rows = within(screen.getByRole("list", { name: "Models the script uses" })).getAllByRole(
			"listitem",
		);
		await expect(rows.map((row) => row.textContent)).toEqual([
			"Decision modelRequiredNot set for In-house membersAssign a decision model",
			"Reranking modelNot set for Cloud members and members who have not chosenAssign a reranking model",
		]);
		await expect(screen.getByRole("link", { name: "Assign a reranking model" })).toHaveAttribute(
			"href",
			"/w/demo/admin/models?purpose=PRACTICE_RERANKING",
		);
	},
};

/** A script that calls only the review model has nothing to assign, but it does use a model. */
export const PrecomputeUsesOnlyTheReviewModel: Story = {
	args: {
		state: scriptedWith({ chat: "required" }),
		precompute: needs({
			needs: [{ purpose: "PRACTICE_REVIEW", need: "REQUIRED", unmetTiers: [] }],
		}),
	},
	play: async () => {
		await openScript();
		await expectSettledVisible(await screen.findByText("It uses only the review model."));
		await expect(
			screen.queryByRole("list", { name: "Models the script uses" }),
		).not.toBeInTheDocument();
		await expect(screen.queryByRole("note")).not.toBeInTheDocument();
	},
};

/** The script's disclosure sits where a review would reach it: after scope, before delivery. */
export const DisclosureOrder: Story = {
	args: {
		state: ready({
			practice: {
				...practice,
				precomputeScript: precomputeScript(),
				deliveryBehavior: { summaryOnly: true },
			},
		}),
		precompute: needs(),
	},
	play: async () => {
		const decides = await screen.findByRole("button", { name: "How it decides" });
		await expectSettledVisible(decides);
		const scope = screen.getByRole("button", { name: "Review scope and evidence" });
		const script = screen.getByRole("button", { name: "Precompute script" });
		const delivery = screen.getByRole("button", { name: "How feedback is delivered" });
		await expect(
			precedes(decides, scope) && precedes(scope, script) && precedes(script, delivery),
		).toBe(true);
	},
};

/** No review has run this script yet, so there are no needs to show and no review to point at. */
export const PrecomputeShownAfterNextReview: Story = {
	args: { state: scripted, precompute: { status: "ready", summary: undefined } },
	play: async () => {
		await openScript();
		await expectSettledVisible(await screen.findByText("Its models show after its next review."));
		await expect(
			screen.queryByRole("link", { name: /^As of the review/u }),
		).not.toBeInTheDocument();
	},
};

/** The newest review ran an earlier version of the script, so its needs would describe other code. */
export const PrecomputeScriptChanged: Story = {
	args: {
		state: scripted,
		precompute: needs({
			asOf: undefined,
			scriptChanged: true,
			needs: [{ purpose: "PRACTICE_DECISION", need: "REQUIRED", unmetTiers: ["IN_HOUSE"] }],
		}),
	},
	play: async () => {
		await openScript();
		await expectSettledVisible(
			await screen.findByText("The script changed. Its models show after its next review."),
		);
		await expect(screen.queryByText("Decision model")).not.toBeInTheDocument();
		await expect(screen.queryByRole("note")).not.toBeInTheDocument();
	},
};

export const PrecomputeNeedsLoading: Story = {
	args: { state: scripted, precompute: { status: "loading" } },
	play: async () => {
		await openScript();
		const panel = await settledDrawerPanel();
		// A skeleton in the rows' shape, so nothing jumps when the needs land.
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
		await expect(
			screen.queryByText("Its models show after its next review."),
		).not.toBeInTheDocument();
	},
};

export const PrecomputeNeedsFailed: Story = {
	args: {
		state: scripted,
		precompute: { status: "error", error: new Error("offline"), onRetry: fn() },
	},
	play: async ({ args }) => {
		await openScript();
		const retry = await screen.findByRole("button", { name: "Retry" });
		await expectSettledVisible(retry);
		await userEvent.click(retry);
		const { precompute } = args;
		await expect(precompute.status === "error" && precompute.onRetry).toHaveBeenCalledOnce();
	},
};
