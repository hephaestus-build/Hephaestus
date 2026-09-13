import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";
import type { AgentBinding, PracticeReviewCoveragePreview } from "@/api/types.gen";
import { expectGenuinelyDisabled, expectUnavailable } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";
import { PracticeReviewSettings } from "./PracticeReviewSettings";
import { mockReviewSettings } from "./story-mock-data";

const settings = mockReviewSettings({ deliverToMerged: false });
const readyBinding: AgentBinding = {
	dataHandlingTier: "IN_HOUSE",
	purpose: "PRACTICE_REVIEW",
	enabled: true,
	ready: true,
	instanceModelId: 1,
};
const model = {
	status: "ready" as const,
	binding: readyBinding,
};
const workspace = {
	enabled: true,
	autoTriggerEnabled: true,
	manualTriggerEnabled: true,
	isSaving: false,
	onUpdate: fn(),
};
const policy = {
	settings,
	isSaving: false,
	onUpdate: fn(),
	onReset: fn(),
};
const coverage = {
	preview: fn(async () => ({
		current: settings.coverageSummary,
		proposed: settings.coverageSummary,
		widens: true,
	})),
	repositories: {
		status: "ready" as const,
		options: ["acme/widgets", "acme/gadgets"].map((value) => ({ value, label: value })),
	},
	people: {
		status: "ready" as const,
		options: [
			{ value: 7, label: "Ada Lovelace", description: "@ada" },
			{ value: 8, label: "Grace Hopper", description: "@grace" },
		],
	},
};
const selectedSettings = mockReviewSettings({
	reviewScope: {
		repositoryMode: "SELECTED",
		personMode: "SELECTED",
		repositories: [{ nameWithOwner: "acme/widgets", baseBranches: ["main"] }],
		personUserIds: [7],
	},
	coverageSummary: {
		...settings.coverageSummary,
		coveredRepositories: 1,
		coveredPeople: 1,
	},
});

const meta = {
	title: "Workspace admin/Practices/Review/When and where",
	component: PracticeReviewSettings,
	parameters: { layout: "padded", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: { workspaceSlug: "acme", model, workspace, policy, coverage },
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
		await expect(canvas.getByRole("switch", { name: "Send feedback" })).toBeChecked();
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

export const SelectedPopulation: Story = {
	args: { policy: { ...policy, settings: selectedSettings } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("1 of 3 monitored repositories")).toBeVisible();
		await expect(canvas.getByText("1 of 8 eligible people")).toBeVisible();
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
			settings: mockReviewSettings({
				reviewScope: {
					repositoryMode: "SELECTED",
					personMode: "SELECTED",
					repositories: [{ nameWithOwner: longRepository, baseBranches: [longBranch] }],
					personUserIds: [7],
				},
			}),
		},
		coverage: {
			...coverage,
			repositories: {
				status: "ready",
				options: [{ value: longRepository, label: longRepository }],
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
					repositories: [{ nameWithOwner: "acme/retired-service", baseBranches: ["main"] }],
					personUserIds: [99],
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not monitored")).toBeVisible();
		await expect(canvas.getByTitle("Member 99 (unavailable)")).toBeVisible();
	},
};

export const OptionsLoadingKeepsPersistedTargetsNeutral: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		coverage: {
			...coverage,
			repositories: { status: "loading" },
			people: { status: "loading" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("acme/widgets")).toBeVisible();
		await expect(canvas.queryByText("Not monitored")).not.toBeInTheDocument();
		await expect(canvas.getByRole("combobox", { name: "Choose repositories" })).toBeDisabled();
	},
};

export const OptionLoadFailure: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		coverage: {
			...coverage,
			repositories: { status: "error", error: new Error("offline"), onRetry: fn() },
			people: { status: "error", error: new Error("offline"), onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Couldn't load repositories")).toBeVisible();
		await expect(canvas.getByText("Couldn't load eligible members")).toBeVisible();
		await expect(canvas.queryByText("Not monitored")).not.toBeInTheDocument();
	},
};

export const CumulativeWideningDraft: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings, onUpdate: fn() },
		coverage: {
			...coverage,
			preview: fn(async () => ({
				current: selectedSettings.coverageSummary,
				proposed: settings.coverageSummary,
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
		await userEvent.click(canvas.getByRole("radio", { name: "All eligible linked members" }));
		await expect(args.coverage.preview).not.toHaveBeenCalled();
		await expect(canvas.getByText("You have unsaved coverage changes.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await expect(dialog.getByText(/Monitored repositories covered:/)).toHaveTextContent(
			"Monitored repositories covered: 1 → 3 of 3",
		);
		await expectNoPageOverflow();
		await userEvent.click(dialog.getByRole("button", { name: "Apply wider coverage" }));
		await expect(args.policy.onUpdate).toHaveBeenCalledWith(
			{
				reviewScope: {
					repositoryMode: "ALL_MONITORED",
					personMode: "ALL_ELIGIBLE",
					repositories: [{ nameWithOwner: "acme/widgets", baseBranches: ["main"] }],
					personUserIds: [7],
				},
			},
			selectedSettings.etag,
		);
	},
};

export const NarrowingAppliesAfterOnePreview: Story = {
	args: {
		policy: { ...policy, onUpdate: fn() },
		coverage: {
			...coverage,
			preview: fn(async () => ({
				current: settings.coverageSummary,
				proposed: { ...settings.coverageSummary, coveredRepositories: 0, coveredPeople: 0 },
				widens: false,
			})),
		},
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Selected repositories" }));
		await userEvent.click(canvas.getByRole("radio", { name: "Selected people" }));
		await expect(args.coverage.preview).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(args.coverage.preview).toHaveBeenCalledTimes(1);
		await expect(args.policy.onUpdate).toHaveBeenCalledTimes(1);
	},
};

export const CoveragePreviewPending: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		coverage: {
			...coverage,
			preview: fn(() => new Promise<PracticeReviewCoveragePreview>(() => {})),
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "All monitored repositories" }));
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(canvas.getByRole("status")).toHaveTextContent("Checking impact…");
		await expect(canvas.getByRole("button", { name: "Checking impact…" })).toBeDisabled();
	},
};

export const CoveragePreviewUnavailable: Story = {
	args: {
		policy: { ...policy, settings: selectedSettings },
		coverage: {
			...coverage,
			preview: fn(async () => {
				throw new Error("preview unavailable");
			}),
		},
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "All monitored repositories" }));
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"Couldn't estimate the impact. Your draft is unchanged; try again.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(args.coverage.preview).toHaveBeenCalledTimes(2);
	},
};

export const CoverageSavePending: Story = {
	args: {
		policy: {
			...policy,
			onUpdate: fn(() => new Promise<void>(() => {})),
		},
		coverage: {
			...coverage,
			preview: fn(async () => ({
				current: settings.coverageSummary,
				proposed: { ...settings.coverageSummary, coveredRepositories: 0, coveredPeople: 0 },
				widens: false,
			})),
		},
	},
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Selected repositories" }));
		await userEvent.click(canvas.getByRole("radio", { name: "Selected people" }));
		await userEvent.click(canvas.getByRole("button", { name: "Review changes" }));
		await expect(args.policy.onUpdate).toHaveBeenCalledTimes(1);
		await expect(canvas.getByText("Saving coverage…")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
	},
};

export const CoverageSaveRollsBack: Story = {
	args: {
		policy: {
			...policy,
			onUpdate: fn(async () => {
				throw new Error("conflict");
			}),
		},
		coverage: {
			...coverage,
			preview: fn(async () => ({
				current: settings.coverageSummary,
				proposed: { ...settings.coverageSummary, coveredRepositories: 0, coveredPeople: 0 },
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
			"Couldn't save the coverage. Your draft is unchanged; try again.",
		);
		await expect(canvas.getByRole("radio", { name: "Selected repositories" })).toBeChecked();
		await expect(canvas.getByRole("radio", { name: "Selected people" })).toBeChecked();
	},
};

export const SendingPausedInDarkMode: Story = {
	args: { policy: { ...policy, settings: { ...settings, deliveryStatus: "PAUSED" } } },
	globals: { theme: "dark" },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Sending is paused")).toBeVisible();
		await expect(canvas.getByRole("switch", { name: "Send feedback" })).not.toBeChecked();
		await expectNoPageOverflow();
	},
};
