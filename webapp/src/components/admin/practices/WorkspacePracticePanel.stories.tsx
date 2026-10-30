import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import type { PracticeScriptSummary } from "@/api/types.gen";
import { mockDefinitionOptions } from "@/components/admin/practice-editor/fixtures";
import {
	catalogOrigin,
	inheritedFeedbackMode,
	mockPractices,
	practiceScript,
} from "@/components/admin/practices/fixtures";
import { PURPOSE_STATUS_DEFS } from "@/components/practice-vocabulary/purpose-status-defs";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";
import { precedes } from "@/test/dom";

import {
	type PracticeScriptNeedsState,
	WorkspacePracticePanel,
	type WorkspacePracticeState,
} from "./WorkspacePracticePanel";

const [practice] = mockPractices;
if (!practice) {
	throw new Error("The shared practice fixtures no longer hold a practice to show");
}

type ReadyState = Extract<WorkspacePracticeState, { status: "ready" }>;

const { name } = currentDefinition(practice);

const ready = (over: Partial<ReadyState> = {}): ReadyState => ({
	status: "ready",
	practice,
	definitionOptions: mockDefinitionOptions,
	groupName: "Review-ready work",
	...over,
});

const scriptedWith = (models: Parameters<typeof practiceScript>[0]) =>
	ready({
		practice: {
			...practice,
			currentVersion: {
				...practice.currentVersion,
				definition: { ...currentDefinition(practice), script: practiceScript(models) },
			},
		},
	});

const scripted = scriptedWith({ decision: "required" });

const REVIEW_ID = "6f1c2b7a-0d3e-4c55-9a51-2f7a3e8b9c10";

const needs = (over: Partial<PracticeScriptSummary> = {}): PracticeScriptNeedsState => ({
	status: "ready",
	summary: {
		practiceSlug: practice.slug,
		practiceName: name,
		asOf: { jobId: "41", reviewId: REVIEW_ID, finishedAt: daysBefore(7) },
		scriptChanged: false,
		needs: [],
		...over,
	},
});

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
		practiceScript: { status: "ready", summary: undefined },
		path: { behind: [{ label: "Practice setup", depth: 0 }], onClose: fn() },
		onResetToCatalog: fn(),
	},
	argTypes: {
		state: { control: false },
		practiceScript: { control: false },
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
		await expect(screen.getByRole("heading", { name, level: 2 })).toBeVisible();
		await expect(screen.getByText("Version 1")).toBeVisible();
		// The path says where the level sits, and its crumb closes back down to the page.
		const path = screen.getByRole("list", { name: "Path" });
		await expect(path).toHaveTextContent(/Practice setup.*Practice/u);
		await userEvent.click(within(path).getByRole("button", { name: "Practice setup" }));
		await expect(args.path.onClose).toHaveBeenCalledWith(0);
	},
};

export const FromTheCatalog: Story = {
	args: {
		state: ready({ practice: { ...practice, catalogOrigin: catalogOrigin(practice.slug) } }),
	},
	play: async () => {
		// What the badge in the catalog list can only label is spelled out here, in the page it opens.
		await expectSettledVisible(await screen.findByText(/does not edit your copy/u));
		await expect(
			screen.queryByRole("button", { name: "Reset to catalog" }),
		).not.toBeInTheDocument();
	},
};

export const EditedHere: Story = {
	args: {
		state: ready({
			practice: {
				...practice,
				catalogOrigin: catalogOrigin(practice.slug, "EDITED_HERE", ["CRITERIA"]),
			},
		}),
	},
	play: async ({ args }) => {
		const reset = await screen.findByRole("button", { name: "Reset to catalog" });
		await expectSettledVisible(reset);
		await userEvent.click(reset);
		await expect(args.onResetToCatalog).toHaveBeenCalledOnce();
	},
};

export const UpdateWaiting: Story = {
	args: {
		state: ready({
			practice: { ...practice, catalogOrigin: catalogOrigin(practice.slug, "UPDATE_WAITING") },
		}),
	},
	play: async () => {
		// The note says where the update is decided, and the link there carries that page's title.
		const updates = await screen.findByRole("button", { name: "Catalog updates" });
		await expectSettledVisible(updates);
		await expect(updates).toHaveAttribute(
			"href",
			expect.stringMatching(/\/admin\/practices\/releases$/u),
		);
		await expect(screen.getByRole("button", { name: "Reset to catalog" })).toBeVisible();
	},
};

export const InheritedFeedbackMode: Story = {
	args: {
		state: ready({
			practice: { ...practice, feedbackMode: inheritedFeedbackMode("APPROVE_FIRST", "GROUP") },
		}),
	},
	play: async () => {
		// An inherited value says where it came from, so the reader knows where to go to change it.
		await expectSettledVisible(await screen.findByText(/Follows Review-ready work/u));
	},
};

export const Unassigned: Story = {
	args: {
		state: ready({ practice: { ...practice, groupSlug: null }, groupName: undefined }),
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

async function openScript() {
	const trigger = await screen.findByRole("button", { name: "Practice script" });
	await expectSettledVisible(trigger);
	await userEvent.click(trigger);
	return trigger;
}

export const PracticeScriptNoScript: Story = {
	args: { practiceScript: needs() },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("link", { name: "Edit practice" }));
		await expect(screen.queryByText(/practice script/iu)).not.toBeInTheDocument();
		await expect(screen.queryByRole("note")).not.toBeInTheDocument();
	},
};

export const PracticeScriptUsesNoModel: Story = {
	args: { state: scriptedWith({}), practiceScript: needs() },
	play: async () => {
		await openScript();
		await expectSettledVisible(await screen.findByText("It uses no model."));
		const asOf = screen.getByRole("link", { name: /^As of the review on \d{1,2} \w{3}$/u });
		await expect(asOf.getAttribute("href")).toMatch(/^\/w\/demo\/admin\/practices\/reviews\?/u);
		await expect(levelsOpenedBy(asOf)).toStrictEqual([`review:${REVIEW_ID}`]);
	},
};

export const PracticeScriptOptionalMet: Story = {
	args: {
		state: scriptedWith({ embedding: "optional" }),
		practiceScript: needs({
			needs: [{ slot: "embedding", need: "optional", unmetTiers: [] }],
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

export const PracticeScriptRequiredUnmet: Story = {
	args: {
		state: scriptedWith({ chat: "required", decision: "required", reranking: "optional" }),
		practiceScript: needs({
			needs: [
				{ slot: "decision", need: "required", unmetTiers: ["IN_HOUSE"] },
				{ slot: "chat", need: "required", unmetTiers: [] },
				{ slot: "reranking", need: "optional", unmetTiers: ["CLOUD", "UNDECLARED"] },
			],
		}),
	},
	play: async () => {
		const alert = await screen.findByRole("note");
		await expectSettledVisible(alert);
		await expect(alert).toHaveTextContent(
			"The practice script does not run for In-house members’ work: no decision model is assigned for them. Assign a decision model",
		);
		await expect(
			within(alert).getByRole("link", { name: "Assign a decision model" }),
		).toHaveAttribute("href", "/w/demo/admin/models?purpose=PRACTICE_DECISION");
		await expect(getComputedStyle(alert).color).toBe(
			toneColour(PURPOSE_STATUS_DEFS.NEEDS_ATTENTION.badgeVariant),
		);
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

export const PracticeScriptUsesOnlyTheReviewModel: Story = {
	args: {
		state: scriptedWith({ chat: "required" }),
		practiceScript: needs({
			needs: [{ slot: "chat", need: "required", unmetTiers: [] }],
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

export const DisclosureOrder: Story = {
	args: { state: scriptedWith({}), practiceScript: needs() },
	play: async () => {
		const decides = await screen.findByRole("button", { name: "How it decides" });
		await expectSettledVisible(decides);
		const settings = screen.getByRole("region", { name: "Review settings" });
		const script = screen.getByRole("button", { name: "Practice script" });
		await expect(precedes(settings, decides) && precedes(decides, script)).toBe(true);
	},
};

export const PracticeScriptShownAfterNextReview: Story = {
	args: { state: scripted, practiceScript: { status: "ready", summary: undefined } },
	play: async () => {
		await openScript();
		await expectSettledVisible(await screen.findByText("Its models show after its next review."));
		await expect(
			screen.queryByRole("link", { name: /^As of the review/u }),
		).not.toBeInTheDocument();
	},
};

export const PracticeScriptChanged: Story = {
	args: {
		state: scripted,
		practiceScript: needs({
			asOf: null,
			scriptChanged: true,
			needs: [{ slot: "decision", need: "required", unmetTiers: ["IN_HOUSE"] }],
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

export const PracticeScriptNeedsLoading: Story = {
	args: { state: scripted, practiceScript: { status: "loading" } },
	play: async () => {
		await openScript();
		const panel = await settledDrawerPanel();
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
		await expect(
			screen.queryByText("Its models show after its next review."),
		).not.toBeInTheDocument();
	},
};

export const PracticeScriptNeedsFailed: Story = {
	args: {
		state: scripted,
		practiceScript: { status: "error", error: new Error("offline"), onRetry: fn() },
	},
	play: async ({ args }) => {
		await openScript();
		const retry = await screen.findByRole("button", { name: "Retry" });
		await expectSettledVisible(retry);
		await userEvent.click(retry);
		const { practiceScript: needsState } = args;
		await expect(needsState.status === "error" && needsState.onRetry).toHaveBeenCalledOnce();
	},
};
