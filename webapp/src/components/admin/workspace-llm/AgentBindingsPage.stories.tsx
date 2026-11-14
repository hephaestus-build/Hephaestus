import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { AgentBinding, AvailableLlmModel } from "@/api/types.gen";
import { DATA_HANDLING_DEFS } from "@/components/practice-vocabulary/data-handling-defs";
import {
	PURPOSE_STATUS_DEFS,
	type PurposeStatus,
} from "@/components/practice-vocabulary/purpose-status-defs";
import { withStandardPage } from "@/stories/decorators";
import { expectControlOnScreen, expectNoPageOverflow } from "@/stories/reflow";
import { WorkspaceLlmProviderPanel } from "./WorkspaceLlmProviderPanel";

import { AgentBindingsPage, bindingTargetKey } from "./AgentBindingsPage";
import { mockAvailableModels, SERVED_ALONE } from "./fixtures";

type Scope = StoryContext["canvas"];

const UNCHOSEN_ROW = "Members who have not chosen";

function trigger(canvas: Scope, title: string): HTMLElement {
	return canvas.getByRole("button", { name: new RegExp(`^${title}`, "u") });
}

async function openPurpose(canvas: Scope, title: string): Promise<Scope> {
	const button = trigger(canvas, title);
	if (button.getAttribute("aria-expanded") !== "true") {
		await userEvent.click(button);
	}
	return within(await canvas.findByRole("region", { name: title }));
}

function row(purpose: Scope, title: string): Scope {
	return within(purpose.getByRole("group", { name: title }));
}

async function openAdvanced(rowScope: Scope) {
	await userEvent.click(rowScope.getByRole("button", { name: /^Advanced/u }));
}

function modelDeclaredAs(tier: AvailableLlmModel["dataHandlingTier"]): AvailableLlmModel {
	const model = mockAvailableModels.find((candidate) => candidate.dataHandlingTier === tier);
	if (!model) {
		throw new Error(`No shared fixture is declared as ${tier}`);
	}
	return model;
}

const inHouseModel = modelDeclaredAs("IN_HOUSE");
const cloudModel = modelDeclaredAs("CLOUD");
const undeclaredModel = modelDeclaredAs("UNDECLARED");

function binding(
	tier: AgentBinding["dataHandlingTier"],
	model: AvailableLlmModel,
	overrides: Partial<AgentBinding> = {},
): AgentBinding {
	const live = (overrides.enabled ?? true) && (overrides.ready ?? true);
	return {
		purpose: "PRACTICE_REVIEW",
		dataHandlingTier: tier,
		instanceModelId: model.scope === "SHARED" ? model.id : undefined,
		workspaceModelId: model.scope === "WORKSPACE" ? model.id : undefined,
		enabled: true,
		ready: true,
		timeoutSeconds: 600,
		servedTiers: live ? [...SERVED_ALONE[tier]] : [],
		...overrides,
	};
}

const partiallyCovered: AgentBinding[] = [
	binding("IN_HOUSE", inHouseModel),
	binding("CLOUD", cloudModel, { ready: false }),
	binding("UNDECLARED", undeclaredModel),
];

const everyoneCovered: AgentBinding[] = [
	binding("IN_HOUSE", inHouseModel),
	binding("IN_HOUSE", inHouseModel, { purpose: "MENTOR" }),
];

const reviewsOnBothTiers: AgentBinding[] = [
	binding("IN_HOUSE", inHouseModel, { servedTiers: ["IN_HOUSE"] }),
	binding("IN_HOUSE", inHouseModel, { purpose: "MENTOR" }),
	binding("CLOUD", cloudModel),
];

const statusLabel = (status: PurposeStatus) => PURPOSE_STATUS_DEFS[status].label;

async function expectNoStatusBadge(canvas: Scope) {
	for (const status of Object.keys(PURPOSE_STATUS_DEFS).filter(isPurposeStatus)) {
		await expect(canvas.queryByText(statusLabel(status), { ignore: ".sr-only" })).toBeNull();
	}
}

const isPurposeStatus = (value: string): value is PurposeStatus =>
	Object.hasOwn(PURPOSE_STATUS_DEFS, value);

const meta = {
	component: AgentBindingsPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "acme",
		state: { status: "ready" },
		bindings: [binding("IN_HOUSE", inHouseModel)],
		availableModels: mockAvailableModels,
		practicesEnabled: true,
		aiChoiceRequired: false,
		ownProviderAllowed: true,
		pendingWrites: new Map(),
		onFocusPurposeClosed: fn(),
		onSave: fn(),
		onTurnOff: fn(),
	},
} satisfies Meta<typeof AgentBindingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The page explains, through the admin guide, how a member's AI choice limits the models below. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", {
				name: "How a member’s AI choice limits which model serves them (opens in a new tab)",
			}),
		).toHaveAttribute(
			"href",
			expect.stringMatching(/\/user\/ai-models#data-handling-and-ai-choices$/u),
		);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Reviews and conversations" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /Practice reviews/u })).toBeNull();
		await expect(screen.queryByRole("status")).toBeNull();
	},
};

export const LoadForbidden: Story = {
	args: {
		state: {
			status: "error",
			error: {
				type: "about:blank",
				title: "Forbidden",
				status: 403,
				detail: "You are not an admin of this workspace.",
				instance: "/workspaces/acme/agents",
			},
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("You do not have access to AI models")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).toBeNull();
	},
};

const retryLoad = fn();

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 503, title: "Service Unavailable" },
			onRetry: retryLoad,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load AI models")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /Practice reviews/u })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retryLoad).toHaveBeenCalledOnce();
	},
};

export const Quiet: Story = {
	args: { bindings: everyoneCovered, aiChoiceRequired: true },
	play: async ({ canvas }) => {
		for (const title of ["Practice reviews", "Conversations"]) {
			await expect(trigger(canvas, title)).toHaveAttribute("aria-expanded", "false");
		}
		await expectNoStatusBadge(canvas);
		await expect(trigger(canvas, "Practice reviews")).toHaveAccessibleName(
			expect.stringContaining(
				`In-house: ${inHouseModel.displayName} Cloud: ${inHouseModel.displayName} In-house`,
			),
		);
	},
};

export const PartlySet: Story = {
	args: { bindings: [binding("CLOUD", cloudModel)], aiChoiceRequired: true },
	play: async ({ canvas }) => {
		const reviews = trigger(canvas, "Practice reviews");
		await expect(reviews).toHaveAccessibleName(
			expect.stringContaining(
				`In-house: none Cloud: ${cloudModel.displayName} ${statusLabel("PARTLY_SET")}`,
			),
		);
		await expect(
			within(reviews).getByText(statusLabel("PARTLY_SET"), { ignore: ".sr-only" }),
		).toBeVisible();
		await expect(reviews).toHaveAttribute("aria-expanded", "false");
	},
};

export const TiersPartiallyCovered: Story = {
	args: { bindings: partiallyCovered },
	play: async ({ canvas }) => {
		const reviewsTrigger = trigger(canvas, "Practice reviews");
		await expect(reviewsTrigger).toHaveAttribute("aria-expanded", "true");
		await expect(reviewsTrigger).toHaveAccessibleName(
			expect.stringContaining(
				`In-house: ${inHouseModel.displayName} Cloud: ${inHouseModel.displayName} In-house Not chosen: ${undeclaredModel.displayName} ${statusLabel("NEEDS_ATTENTION")}`,
			),
		);
		const reviews = within(canvas.getByRole("region", { name: "Practice reviews" }));

		await expect(row(reviews, "In-house").queryByText(/ready/iu)).toBeNull();
		await expect(row(reviews, "Cloud").getByText("Not ready")).toBeVisible();

		await userEvent.click(row(reviews, "Cloud").getByRole("combobox", { name: /Cloud/u }));
		await expect(await screen.findByRole("option", { name: /^GPT-5 /u })).toBeVisible();
		await expect(screen.queryByRole("option", { name: /Local Llama/u })).toBeNull();
		await expect(screen.queryByRole("option", { name: /My OpenAI key/u })).toBeNull();
	},
};

export const NothingCovered: Story = {
	args: { bindings: [binding("UNDECLARED", undeclaredModel)] },
	play: async ({ canvas }) => {
		await expect(trigger(canvas, "Practice reviews")).toHaveAccessibleName(
			expect.stringContaining(
				`In-house: none Cloud: none Not chosen: ${undeclaredModel.displayName} ${statusLabel("PARTLY_SET")}`,
			),
		);
		const unchosen = row(await openPurpose(canvas, "Practice reviews"), UNCHOSEN_ROW);
		await expect(unchosen.getByRole("heading", { level: 4 })).toHaveTextContent(UNCHOSEN_ROW);
		await expect(unchosen.queryByText(DATA_HANDLING_DEFS.UNDECLARED.label)).toBeNull();
	},
};

export const ChoiceRequired: Story = {
	args: { bindings: partiallyCovered, aiChoiceRequired: true },
	play: async ({ canvas }) => {
		await expect(trigger(canvas, "Practice reviews")).not.toHaveAccessibleName(/Not chosen/u);
		await expect(canvas.queryByText("Not chosen")).toBeNull();
		const reviews = await openPurpose(canvas, "Practice reviews");
		await expect(row(reviews, UNCHOSEN_ROW).getByText(/no one uses it now/u)).toBeVisible();
		const conversations = await openPurpose(canvas, "Conversations");
		await expect(conversations.queryByRole("group", { name: UNCHOSEN_ROW })).toBeNull();
	},
};

export const SlotRejected: Story = {
	args: {
		bindings: partiallyCovered,
		availableModels: [
			...mockAvailableModels,
			{ ...inHouseModel, id: 99, displayName: "Local Qwen (self-hosted)" },
		],
		saveErrors: {
			[bindingTargetKey({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" })]:
				"This model is declared as Cloud. Assign it under Cloud.",
		},
	},
	play: async ({ canvas, args }) => {
		const inHouse = row(await openPurpose(canvas, "Practice reviews"), "In-house");
		await userEvent.click(inHouse.getByRole("button", { name: /^Save assignment/u }));
		await expect(args.onSave).toHaveBeenCalledWith(
			{ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
			expect.objectContaining({ instanceModelId: 2 }),
		);
		const picker = inHouse.getByRole("combobox", { name: /In-house/u });
		await expect(picker).toHaveTextContent("Local Llama (self-hosted)");
		await expect(picker).toHaveAttribute("aria-invalid", "true");
		await expect(inHouse.getByRole("alert")).toHaveTextContent(
			"This model is declared as Cloud. Assign it under Cloud.",
		);
		await expect(picker).toHaveAccessibleDescription(
			/This model is declared as Cloud\. Assign it under Cloud\./u,
		);

		await userEvent.click(picker);
		await userEvent.click(await screen.findByRole("option", { name: /^Local Qwen/u }));
		await expect(picker).toHaveTextContent("Local Qwen (self-hosted)");
		await expect(picker).toHaveAttribute("aria-invalid", "false");
		await expect(inHouse.queryByRole("alert")).toBeNull();
		await expect(picker).not.toHaveAccessibleDescription(/declared as Cloud/u);
	},
};

/** A bound model that was re-declared keeps its name on the row, which says why it stopped. */
export const BoundModelMoved: Story = {
	args: {
		bindings: [binding("IN_HOUSE", cloudModel, { ready: false })],
	},
	play: async ({ canvas }) => {
		const inHouse = row(await openPurpose(canvas, "Practice reviews"), "In-house");
		await expect(inHouse.getByText("Not ready")).toBeVisible();
		const picker = inHouse.getByRole("combobox", { name: /In-house/u });
		await expect(picker).toHaveTextContent("GPT-5");
		await expect(picker).toHaveAccessibleDescription(
			"GPT-5 is now declared as Cloud and no longer serves this assignment. Choose another model, or clear the assignment.",
		);
	},
};

/** With no other model of the row's tier on offer, the picker has nothing to choose, so the hint says so. */
export const BoundModelMovedNoAlternative: Story = {
	args: {
		bindings: [binding("IN_HOUSE", cloudModel, { ready: false })],
		availableModels: [cloudModel],
	},
	play: async ({ canvas }) => {
		const inHouse = row(await openPurpose(canvas, "Practice reviews"), "In-house");
		const picker = inHouse.getByRole("combobox", { name: /In-house/u });
		await expect(picker).toBeDisabled();
		await expect(picker).toHaveAccessibleDescription(
			"GPT-5 is now declared as Cloud and no longer serves this assignment. Clear the assignment, or ask an instance admin for a model declared as In-house.",
		);
		await expect(inHouse.getByRole("button", { name: /^Clear assignment/u })).toBeEnabled();
	},
};

export const NoModelsAvailable: Story = {
	args: {
		bindings: [],
		availableModels: [],
		providerPanel: (
			<WorkspaceLlmProviderPanel
				state={{ status: "ready", connections: [], models: [] }}
				registrationAllowed
				testResults={new Map()}
				testingConnectionIds={new Set()}
				writingConnectionIds={new Set()}
				writingModelIds={new Set()}
				onAddConnection={fn()}
				onEditConnection={fn()}
				onTestConnection={fn()}
				onDisconnect={fn()}
				onAddModel={fn()}
				onEditModel={fn()}
				onDeleteModel={fn()}
			/>
		),
	},
	play: async ({ canvas }) => {
		await expect(trigger(canvas, "Practice reviews")).toHaveAttribute("aria-expanded", "true");
		const reviews = within(canvas.getByRole("region", { name: "Practice reviews" }));
		await expect(
			row(reviews, "Cloud").getByText(/No model for this use declared as/u),
		).toHaveTextContent(
			"No model for this use declared as Cloud is available yet. Ask an instance admin, or add one to a provider under Your providers.",
		);
		await expect(
			row(reviews, UNCHOSEN_ROW).getByText(/No model for this use is available yet/u),
		).toBeVisible();
		await expect(canvas.getByText("Connect your own provider")).toBeVisible();
	},
};

export const NoModelsOwnProvidersBlocked: Story = {
	args: { bindings: [], availableModels: [], ownProviderAllowed: false },
	play: async ({ canvas }) => {
		const reviews = within(canvas.getByRole("region", { name: "Practice reviews" }));
		await expect(
			row(reviews, "Cloud").getByText(/No model for this use declared as/u),
		).toHaveTextContent(
			"No model for this use declared as Cloud is available yet. Ask an instance admin to share one.",
		);
		await expect(
			row(reviews, UNCHOSEN_ROW).getByText(/No model for this use is available yet/u),
		).toHaveTextContent(
			"No model for this use is available yet. Ask an instance admin to share one.",
		);
	},
};

export const ProjectReviewsDisabled: Story = {
	args: {
		bindings: partiallyCovered,
		practicesEnabled: false,
	},
	play: async ({ canvas }) => {
		await expect(trigger(canvas, "Practice reviews")).toHaveAccessibleName(
			new RegExp(`${statusLabel("OFF")}$`, "u"),
		);
		await expect(trigger(canvas, "Practice reviews")).toHaveAttribute("aria-expanded", "false");
		await expect(
			canvas.getByText(/Practice reviews are off, so their model is not used\./u),
		).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Open Review settings" })).toHaveAttribute(
			"href",
			"/w/acme/admin/practices/review?section=automated-review",
		);

		await expect(trigger(canvas, "Practice reviews")).toHaveAccessibleName(
			expect.stringContaining(`In-house: none Cloud: none Not chosen: none ${statusLabel("OFF")}`),
		);
		const reviews = await openPurpose(canvas, "Practice reviews");
		await expect(reviews.queryByText("Not ready")).toBeNull();
		await expect(reviews.queryByRole("alert")).toBeNull();
		await expect(
			row(reviews, "In-house").getByRole("button", { name: /^Save assignment/u }),
		).toBeEnabled();
		await expect(trigger(canvas, "Conversations")).toHaveAccessibleName(
			expect.stringContaining(
				`In-house: none Cloud: none Not chosen: none ${statusLabel("NOT_SET")}`,
			),
		);
	},
};

export const Saving: Story = {
	args: {
		bindings: partiallyCovered,
		pendingWrites: new Map([
			[bindingTargetKey({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" }), "SAVE"],
		]),
	},
	play: async ({ canvas }) => {
		const reviews = within(canvas.getByRole("region", { name: "Practice reviews" }));
		const inHouse = row(reviews, "In-house");
		await expect(inHouse.getByRole("button", { name: "Saving… for In-house" })).toBeDisabled();
		await expect(inHouse.getByRole("button", { name: /^Clear assignment/u })).toBeDisabled();
		await expect(
			row(reviews, "Cloud").getByRole("button", { name: /^Save assignment/u }),
		).toBeEnabled();
		const conversations = await openPurpose(canvas, "Conversations");
		await expect(
			row(conversations, "In-house").getByRole("button", { name: /^Save assignment/u }),
		).toBeEnabled();
	},
};

export const Clearing: Story = {
	args: {
		bindings: partiallyCovered,
		pendingWrites: new Map([
			[bindingTargetKey({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" }), "CLEAR"],
		]),
	},
	play: async ({ canvas }) => {
		const reviews = within(canvas.getByRole("region", { name: "Practice reviews" }));
		const inHouse = row(reviews, "In-house");
		await expect(inHouse.getByRole("button", { name: "Clearing… for In-house" })).toBeDisabled();
		await expect(inHouse.getByRole("button", { name: /^Save assignment/u })).toBeDisabled();
		await expect(
			row(reviews, "Cloud").getByRole("button", { name: /^Clear assignment/u }),
		).toBeEnabled();
	},
};

export const DeepLinkedPurpose: Story = {
	args: { bindings: everyoneCovered, focusPurpose: "MENTOR" },
	play: async ({ canvas, args }) => {
		const conversations = trigger(canvas, "Conversations");
		await expect(conversations).toHaveAttribute("aria-expanded", "true");
		await expect(trigger(canvas, "Practice reviews")).toHaveAttribute("aria-expanded", "false");
		await waitFor(async () => {
			const { top } = conversations.getBoundingClientRect();
			await expect(top).toBeLessThan(window.innerHeight);
			await expect(top).toBeGreaterThanOrEqual(0);
		});
		await userEvent.click(conversations);
		await expect(args.onFocusPurposeClosed).toHaveBeenCalledOnce();
	},
};

export const AdvancedDisclosure: Story = {
	play: async ({ canvas }) => {
		const reviews = row(await openPurpose(canvas, "Practice reviews"), "In-house");
		await openAdvanced(reviews);
		// A review's pace follows capacity, so its row offers no count of reviews at once.
		await expect(reviews.getByLabelText(/^Timeout \(seconds\)/u)).toBeVisible();
		await expect(reviews.queryByLabelText(/^Max concurrent reviews/u)).toBeNull();
		const conversations = row(await openPurpose(canvas, "Conversations"), "In-house");
		await openAdvanced(conversations);
		await expect(conversations.queryByLabelText(/^Max concurrent reviews/u)).toBeNull();
		await expect(conversations.getByLabelText(/^Timeout \(seconds\)/u)).toHaveValue(900);
	},
};

export const InvalidRunLimit: Story = {
	play: async ({ canvas }) => {
		const inHouse = row(await openPurpose(canvas, "Practice reviews"), "In-house");
		await openAdvanced(inHouse);

		await userEvent.clear(inHouse.getByLabelText(/^Timeout \(seconds\)/u));
		await userEvent.click(inHouse.getByRole("button", { name: /^Save assignment/u }));

		await expect(await canvas.findByText("Enter a number of seconds.")).toBeVisible();
		await expect(screen.queryByRole("status")).toBeNull();
	},
};

export const MobileReflow: Story = {
	args: { bindings: reviewsOnBothTiers },
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async ({ canvas }) => {
		const reviews = await openPurpose(canvas, "Practice reviews");
		await expectNoPageOverflow();
		const save = row(reviews, "In-house").getByRole("button", { name: /^Save assignment/u });
		// Vertical page scrolling is expected; saving must not need horizontal scrolling.
		save.scrollIntoView({ block: "center" });
		await expectControlOnScreen(save);
	},
};

export const InUse: Story = {
	args: { bindings: reviewsOnBothTiers },
	play: async ({ canvas }) => {
		const glyphTone = (title: string) => {
			const glyph = trigger(canvas, title).querySelector("svg");
			if (glyph === null) {
				throw new Error(`The ${title} row draws no kind mark`);
			}
			return getComputedStyle(glyph).color;
		};
		await expect(glyphTone("Conversations")).toBe(glyphTone("Practice reviews"));
		await expect(trigger(canvas, "Conversations")).toHaveAccessibleName(
			expect.stringContaining(
				`In-house: ${inHouseModel.displayName} Cloud: ${inHouseModel.displayName} In-house Not chosen: none ${statusLabel("PARTLY_SET")}`,
			),
		);
	},
};

export const Dark: Story = {
	args: { bindings: reviewsOnBothTiers },
	globals: { theme: "dark" },
};

export const RefreshFailed: Story = {
	args: {
		bindings: partiallyCovered,
		refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not refresh AI models")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		const inHouse = row(await openPurpose(canvas, "Practice reviews"), "In-house");
		await expect(inHouse.getByRole("combobox", { name: /In-house/u })).toHaveTextContent(
			"Local Llama",
		);
		await expect(inHouse.getByRole("button", { name: /^Save assignment/u })).toBeEnabled();
	},
};

export const RefreshRetrying: Story = {
	args: {
		bindings: partiallyCovered,
		refreshFailure: { error: { status: 503 }, pending: true, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
	},
};
