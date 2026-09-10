import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { AgentBinding } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectControlOnScreen, expectNoPageOverflow } from "@/test/reflow";

import { AgentBindingsPage } from "./AgentBindingsPage";
import { mockAvailableModels } from "./story-mock-data";

function purposeCard(canvas: StoryContext["canvas"], name: string) {
	return within(canvas.getByRole("region", { name }));
}

async function openPracticeReviewAdvanced(canvas: StoryContext["canvas"]) {
	await userEvent.click(
		purposeCard(canvas, "Practice reviews").getByRole("button", { name: /Advanced/ }),
	);
}

const detectionBinding: AgentBinding = {
	processingLocation: "UNCLASSIFIED",
	purpose: "PRACTICE_REVIEW",
	instanceModelId: 1,
	enabled: true,
	ready: true,
	timeoutSeconds: 600,
	maxConcurrentJobs: 3,
	allowInternet: false,
};

const meta = {
	component: AgentBindingsPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "acme",
		bindings: [detectionBinding],
		availableModels: mockAvailableModels,
		practicesEnabled: true,
		mentorEnabled: true,
		isLoading: false,
		isError: false,
		loadError: null,
		pendingPurposes: new Set(),
		onRetry: fn(),
		onSave: fn(),
		onTurnOff: fn(),
	},
} satisfies Meta<typeof AgentBindingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PracticeReviewsBoundHephUnbound: Story = {};

export const Loading: Story = {
	args: { isLoading: true },
};

export const NoModelsAvailable: Story = {
	args: { bindings: [], availableModels: [] },
	play: async ({ canvas }) => {
		await canvas.findAllByText(/No models are available yet/);
	},
};

export const LoadForbidden: Story = {
	args: {
		isError: true,
		loadError: {
			type: "about:blank",
			title: "Forbidden",
			status: 403,
			detail: "You are not an admin of this workspace.",
			instance: "/workspaces/acme/agents",
		},
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Couldn't load AI models")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).toBeNull();
	},
};

export const ProjectReviewsDisabled: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		const card = within(canvas.getByRole("region", { name: "Practice reviews" }));
		card.getByText("Ready");
		card.getByText("Practice reviews off");
		await expect(card.getByRole("link", { name: "Open Review: When and where" })).toHaveAttribute(
			"href",
			"/w/acme/admin/practices/review?section=when-and-where",
		);
		await expect(card.getByRole("button", { name: "Save assignment" })).toBeEnabled();
	},
};

export const OnlyThePendingCardIsFrozen: Story = {
	args: { pendingPurposes: new Set(["PRACTICE_REVIEW" as const]) },
	play: async ({ canvas }) => {
		await expect(
			purposeCard(canvas, "Practice reviews").getByRole("button", { name: "Save assignment" }),
		).toBeDisabled();
		await expect(
			purposeCard(canvas, "Heph").getByRole("button", { name: "Save assignment" }),
		).toBeEnabled();
	},
};

export const AdvancedDisclosure: Story = {
	play: async ({ canvas }) => {
		await openPracticeReviewAdvanced(canvas);
		const reviews = purposeCard(canvas, "Practice reviews");
		await expect(reviews.getByLabelText("Max concurrent runs")).toHaveValue(3);
		const mentor = purposeCard(canvas, "Heph");
		await userEvent.click(mentor.getByRole("button", { name: /Advanced/ }));
		await expect(mentor.queryByLabelText("Max concurrent runs")).toBeNull();
		await expect(mentor.getByLabelText("Timeout (seconds)")).toHaveValue(10800);
	},
};

export const InvalidRunLimit: Story = {
	play: async ({ canvas }) => {
		const card = purposeCard(canvas, "Practice reviews");
		await openPracticeReviewAdvanced(canvas);

		await userEvent.clear(card.getByLabelText("Timeout (seconds)"));
		await userEvent.click(card.getByRole("button", { name: "Save assignment" }));

		await expect(await canvas.findByText("Enter a number of seconds.")).toBeVisible();
		await expect(screen.queryByRole("status")).toBeNull();
	},
};

export const MobileReflow: Story = {
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("Practice reviews");
		await expectNoPageOverflow();
		const save = purposeCard(canvas, "Practice reviews").getByRole("button", {
			name: "Save assignment",
		});
		// Vertical page scrolling is expected; saving must not need horizontal scrolling.
		save.scrollIntoView({ block: "center" });
		await expectControlOnScreen(save);
	},
};
