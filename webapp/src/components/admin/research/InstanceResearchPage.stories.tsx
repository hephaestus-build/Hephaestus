import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { daysAfter, daysBefore } from "@/stories/story-clock";

import { InstanceResearchPage } from "./InstanceResearchPage";

const meta = {
	component: InstanceResearchPage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		program: { status: "ready", organization: "TUM AET", noticeVersion: "2026-10-04" },
		workspaces: {
			status: "ready",
			options: [
				{ id: "2", label: "Intro Course (intro-course)" },
				{ id: "3", label: "Design Lab (design-lab)" },
			],
		},
		passkey: { status: "required", available: true, pending: false, onConfirm: fn() },
		release: { pending: false, completed: 0, onRelease: fn() },
		disclosures: {
			state: { status: "unchecked" },
			onCheck: fn(),
			onRecordRemoved: fn(),
			onClose: fn(),
		},
	},
} satisfies Meta<typeof InstanceResearchPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The question, the duties and the form; nothing is sent without the acknowledgement. */
export const ReadyToRelease: Story = {
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("TUM AET")).toBeVisible();
		await expect(canvas.getByText(/pseudonymous personal data, not anonymous/u)).toBeVisible();
		// The days are required fields, so the browser stops an empty form before it is sent.
		await userEvent.click(canvas.getByRole("button", { name: "Export and download" }));
		await expect(args.release.onRelease).not.toHaveBeenCalled();
		await expect(canvas.getByRole("button", { name: "Confirm with passkey" })).toBeEnabled();
	},
};

/** Without a program nothing new can be exported, but earlier datasets stay workable. */
export const NoResearchProgram: Story = {
	args: {
		program: { status: "ready", noticeVersion: "2026-10-04" },
		disclosures: {
			state: {
				status: "ready",
				revision: 1,
				datasets: [
					{
						datasetId: "6f1c2b8e-4d3a-4f6b-9a1e-2c7d8e9f0a1b",
						workspaceLabel: "Intro Course",
						createdAt: daysBefore(40),
						useUntil: daysAfter(300),
						useExpired: false,
						removals: [
							{
								participantKey: "p_abcdefghijklmnopqrstuvwxyz",
								reason: "QUESTION_CHANGED",
								requiredAt: daysBefore(2),
							},
						],
					},
				],
			},
			onCheck: fn(),
			onRecordRemoved: fn(),
			onClose: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This instance runs no research program")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Export and download" })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Record removal…" })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "Close dataset…" })).toBeEnabled();
	},
};

export const Releasing: Story = {
	args: { release: { pending: true, completed: 0, onRelease: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Exporting…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const Released: Story = {
	args: {
		release: {
			pending: false,
			completed: 1,
			onRelease: fn(),
			outcome: {
				kind: "receipt",
				receipt: {
					schema: "hephaestus.research-dataset",
					schemaVersion: 1,
					datasetId: "6f1c2b8e-4d3a-4f6b-9a1e-2c7d8e9f0a1b",
					classification: "PSEUDONYMOUS_PERSONAL_DATA",
					window: { from: "2026-04-01", to: "2026-10-01" },
					useUntil: "2027-10-01",
					counts: {
						participants: 12,
						results: 340,
						problems: 81,
						feedback: 57,
						feedbackExcludedIncompleteBasis: 2,
					},
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Dataset exported" })).toBeVisible();
		await expect(canvas.getByText(/you cannot download it again/u)).toBeVisible();
	},
};

/** A failed export is not retried; the operator checks the open datasets first. */
export const ReleaseFailed: Story = {
	args: {
		release: {
			pending: false,
			completed: 0,
			onRelease: fn(),
			error: { status: 502, title: "Bad gateway" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Check open datasets before you export again/u)).toBeVisible();
	},
};

export const LoadingQuestion: Story = {
	args: { program: { status: "loading" } },
};

export const NoUsablePasskey: Story = {
	args: { passkey: { status: "required", available: false, pending: false, onConfirm: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Confirm with passkey" })).toBeNull();
		await expect(
			canvas.getByText(/you cannot export, check or update datasets here/u),
		).toBeVisible();
	},
};

/** A test instance that turned additional confirmation off: nothing asks for a passkey. */
export const ConfirmationNotRequired: Story = {
	args: { passkey: { status: "notRequired" } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/Extra authentication checks are disabled on this instance/u),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Confirm with passkey" })).toBeNull();
		await expect(canvas.queryByText(/you cannot export, check or update datasets/u)).toBeNull();
		await expect(canvas.getByRole("button", { name: "Export and download" })).toBeEnabled();
	},
};

/** Until the server answers, the page neither asks for a passkey nor says that none is needed. */
export const LoadingPasskeyStatus: Story = {
	args: { passkey: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/may ask for a recent passkey confirmation/u)).toBeVisible();
		await expect(canvas.queryByText(/checks are disabled/u)).toBeNull();
		await expect(canvas.queryByText(/you cannot export, check or update datasets/u)).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Confirm with passkey" })).toBeNull();
	},
};

/** A failed status read keeps the export: the server still checks the request itself. */
export const PasskeyStatusFailed: Story = {
	args: {
		passkey: { status: "error", error: { status: 503, title: "Unavailable" }, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your passkey status")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByText(/checks are disabled/u)).toBeNull();
		await expect(canvas.queryByText(/you cannot export, check or update datasets/u)).toBeNull();
		await expect(canvas.getByRole("button", { name: "Export and download" })).toBeEnabled();
	},
};

export const NarrowScreen: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
