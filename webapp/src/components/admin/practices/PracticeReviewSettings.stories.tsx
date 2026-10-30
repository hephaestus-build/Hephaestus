import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";
import type { AgentBinding, PracticeReviewScopePreview } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";
import { pending } from "@/test/async";
import { expectGenuinelyDisabled, expectUnavailable } from "@/test/controls";
import { PracticeReviewSettings } from "./PracticeReviewSettings";
import { reviewSettings } from "./review/fixtures";

const settings = reviewSettings({ deliverToMerged: false });
const readyBinding: AgentBinding = {
	dataHandlingTier: "IN_HOUSE",
	purpose: "PRACTICE_REVIEW",
	enabled: true,
	ready: true,
	instanceModelId: 1,
	servedTiers: ["IN_HOUSE", "CLOUD"],
};
const model = {
	status: "ready" as const,
	binding: readyBinding,
};
const workspace = {
	enabled: true,
	startsFromActivity: true,
	startsOnRequest: true,
	isSaving: false,
	onUpdate: fn(),
};
const policy = {
	settings,
	isSaving: false,
	onUpdate: fn(),
	onReset: fn(),
};
const scope = {
	preview: fn(async () => ({
		current: settings.scopeSummary,
		proposed: settings.scopeSummary,
		widens: true,
	})),
	repositories: {
		status: "ready" as const,
		options: [
			{ itemId: "101", name: "acme/widgets" },
			{ itemId: "102", name: "acme/gadgets" },
		],
	},
	people: {
		status: "ready" as const,
		options: [
			{ value: "7", label: "Ada Lovelace", description: "@ada" },
			{ value: "8", label: "Grace Hopper", description: "@grace, not signed in yet" },
		],
	},
};
const selectedSettings = reviewSettings({
	reviewScope: {
		repositories: "SELECTED",
		people: "SELECTED",
		selectedRepositories: [{ repositoryItemId: "101", baseBranches: ["main"] }],
		accountIds: ["7"],
	},
	scopeSummary: {
		...settings.scopeSummary,
		selectedRepositories: 1,
		selectedPeople: 1,
	},
});

const meta = {
	component: PracticeReviewSettings,
	parameters: { layout: "padded", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: { workspaceSlug: "acme", model, workspace, policy, scope },
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof PracticeReviewSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Configured: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Review changes" }));
		// Sending feedback lives in the Feedback mode tab.
		await expect(canvas.queryByRole("switch", { name: "Send feedback" })).not.toBeInTheDocument();
		await expectNoPageOverflow();
	},
};

export const NoModelSelected: Story = {
	args: { model: { ...model, binding: undefined }, workspace: { ...workspace, enabled: false } },
};

export const SelectedModelTurnedOff: Story = {
	args: {
		model: { ...model, binding: { ...readyBinding, enabled: false } },
		workspace: { ...workspace, enabled: false },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The review model is unavailable")).toBeVisible();
		await expectUnavailable(canvas.getByRole("switch", { name: "Start practice reviews" }));
	},
};

export const SelectedScope: Story = {
	args: { policy: { ...policy, settings: selectedSettings } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("1 selected · 3 monitored repositories")).toBeVisible();
		await expect(canvas.getByText("1 of 8 eligible people")).toBeVisible();
	},
};

export const SelectedNobody: Story = {
	args: {
		policy: {
			...policy,
			settings: reviewSettings({
				reviewScope: {
					repositories: "SELECTED",
					people: "SELECTED",
					selectedRepositories: [],
					accountIds: [],
				},
			}),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("0 selected · 3 monitored repositories")).toBeVisible();
		await expect(canvas.getByText("0 of 8 eligible people")).toBeVisible();
		await expect(canvas.getByText("No people are selected")).toBeVisible();
		await expect(canvas.getByText("No repositories are selected")).toBeVisible();
	},
};

const longRepository =
	"acme/identity-and-access-management-service-for-international-workspace-administration";
const longBranch =
	"feature/replace-legacy-workspace-membership-reconciliation-with-provider-scoped-identities";

export const LongRepositoryAndBranchAtReflow: Story = {
	args: {
		policy: {
			...policy,
			settings: reviewSettings({
				reviewScope: {
					repositories: "SELECTED",
					people: "SELECTED",
					selectedRepositories: [{ repositoryItemId: "103", baseBranches: [longBranch] }],
					accountIds: ["7"],
				},
			}),
		},
		scope: {
			...scope,
			repositories: {
				status: "ready",
				options: [{ itemId: "103", name: longRepository }],
			},
		},
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: `Base branches for ${longRepository}` }),
		);
		await expect(canvas.getByTitle(longBranch)).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const PersistedTargetsNoLongerAvailable: Story = {
	args: {
		policy: {
			...policy,
			settings: {
				...selectedSettings,
				reviewScope: {
					...selectedSettings.reviewScope,
					selectedRepositories: [{ repositoryItemId: "199", baseBranches: ["main"] }],
					accountIds: ["99"],
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Source unavailable")).toBeVisible();
		await expect(canvas.getByTitle("Member 99 (unavailable)")).toBeVisible();
	},
};

export const OptionsLoadingKeepsPersistedTargetsNeutral: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		scope: {
			...scope,
			repositories: { status: "loading" },
			people: { status: "loading" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Repository 101")).toBeVisible();
		await expect(canvas.queryByText("Source unavailable")).not.toBeInTheDocument();
		await expect(canvas.getByRole("combobox", { name: "Choose repositories" })).toBeDisabled();
	},
};

export const OptionLoadFailure: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		scope: {
			...scope,
			repositories: { status: "error", error: new Error("offline"), onRetry: fn() },
			people: { status: "error", error: new Error("offline"), onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load repositories")).toBeVisible();
		await expect(canvas.getByText("We could not load eligible members")).toBeVisible();
		await expect(canvas.queryByText("Source unavailable")).not.toBeInTheDocument();
	},
};

export const CumulativeWideningDraft: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings, onUpdate: fn() },
		scope: {
			...scope,
			preview: fn(async () => ({
				current: selectedSettings.scopeSummary,
				proposed: settings.scopeSummary,
				widens: true,
			})),
		},
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "All monitored repositories" }));
		await userEvent.click(canvas.getByRole("radio", { name: "All eligible members" }));
		await expect(args.scope.preview).not.toHaveBeenCalled();
		await expect(canvas.getByText("You have unsaved review scope changes.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(dialog.getByText(/Monitored repositories in scope:/u)).toHaveTextContent(
			"Monitored repositories in scope: 1 → 3 of 3",
		);
		await expectNoPageOverflow();
		await userEvent.click(dialog.getByRole("button", { name: "Widen review scope" }));
		await expect(args.policy.onUpdate).toHaveBeenCalledWith(
			{
				reviewScope: {
					repositories: "ALL",
					people: "ALL",
					selectedRepositories: [{ repositoryItemId: "101", baseBranches: ["main"] }],
					accountIds: ["7"],
				},
			},
			selectedSettings.etag,
		);
	},
};

export const NarrowingAppliesAfterOnePreview: Story = {
	args: {
		policy: { ...policy, onUpdate: fn() },
		scope: {
			...scope,
			preview: fn(async () => ({
				current: settings.scopeSummary,
				proposed: { ...settings.scopeSummary, selectedRepositories: 0, selectedPeople: 0 },
				widens: false,
			})),
		},
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Selected repositories" }));
		await userEvent.click(canvas.getByRole("radio", { name: "Selected people" }));
		await expect(
			canvas.getByText(/With no repositories selected, Hephaestus reviews no repository work/u),
		).toBeVisible();
		await expect(
			canvas.getByText(/With no people selected, Hephaestus reviews nobody’s work/u),
		).toBeVisible();
		await expect(args.scope.preview).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(args.scope.preview).toHaveBeenCalledTimes(1);
		await expect(args.policy.onUpdate).toHaveBeenCalledTimes(1);
	},
};

export const ScopePreviewPending: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		scope: {
			...scope,
			preview: fn(pending<PracticeReviewScopePreview>),
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "All monitored repositories" }));
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(canvas.getByRole("status")).toHaveTextContent("Checking impact…");
		await expect(canvas.getByRole("button", { name: "Checking impact…" })).toBeDisabled();
	},
};

export const ScopePreviewUnavailable: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		scope: {
			...scope,
			preview: fn(async () => {
				throw new Error("preview unavailable");
			}),
		},
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "All monitored repositories" }));
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			/^We could not estimate the impact\./u,
		);
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(args.scope.preview).toHaveBeenCalledTimes(2);
	},
};

export const ScopeSavePending: Story = {
	args: {
		policy: {
			...policy,
			onUpdate: fn(pending),
		},
		scope: {
			...scope,
			preview: fn(async () => ({
				current: settings.scopeSummary,
				proposed: { ...settings.scopeSummary, selectedRepositories: 0, selectedPeople: 0 },
				widens: false,
			})),
		},
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Selected repositories" }));
		await userEvent.click(canvas.getByRole("radio", { name: "Selected people" }));
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(args.policy.onUpdate).toHaveBeenCalledTimes(1);
		await expect(canvas.getByText("Saving review scope…")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
	},
};

export const ScopeSaveRollsBack: Story = {
	args: {
		policy: {
			...policy,
			onUpdate: fn(async () => {
				throw new Error("conflict");
			}),
		},
		scope: {
			...scope,
			preview: fn(async () => ({
				current: settings.scopeSummary,
				proposed: { ...settings.scopeSummary, selectedRepositories: 0, selectedPeople: 0 },
				widens: false,
			})),
		},
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Selected repositories" }));
		await userEvent.click(canvas.getByRole("radio", { name: "Selected people" }));
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(args.policy.onUpdate).toHaveBeenCalledTimes(1);
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			/^We could not save the review scope\./u,
		);
		await expect(canvas.getByRole("radio", { name: "Selected repositories" })).toBeChecked();
		await expect(canvas.getByRole("radio", { name: "Selected people" })).toBeChecked();
	},
};
