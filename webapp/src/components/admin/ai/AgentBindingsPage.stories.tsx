import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { AgentBinding, AvailableLlmModel } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectControlOnScreen, expectNoPageOverflow } from "@/test/reflow";

import { AgentBindingsPage, bindingTargetKey } from "./AgentBindingsPage";
import { mockAvailableModels } from "./story-mock-data";

type Scope = StoryContext["canvas"];

function purposeCard(canvas: Scope, name: string): Scope {
	return within(canvas.getByRole("region", { name }));
}

/** A row is the group its heading names: the tier's label, or the unchosen row's own title. */
function row(card: Scope, title: string): Scope {
	return within(card.getByRole("group", { name: title }));
}

const UNCHOSEN_ROW = "Members who haven't chosen";

function previewRow(card: Scope, term: string): HTMLElement {
	const dt = card.getByText(term, { selector: "dt" });
	const dd = dt.nextElementSibling;
	if (!(dd instanceof HTMLElement)) throw new Error(`No definition for ${term}`);
	return dd;
}

async function openAdvanced(rowScope: Scope) {
	await userEvent.click(rowScope.getByRole("button", { name: /^Advanced/ }));
}

function modelDeclaredAs(tier: AvailableLlmModel["dataHandlingTier"]): AvailableLlmModel {
	const model = mockAvailableModels.find((candidate) => candidate.dataHandlingTier === tier);
	if (!model) throw new Error(`No shared fixture is declared as ${tier}`);
	return model;
}

const inHouseModel = modelDeclaredAs("IN_HOUSE");
const notKeptModel = modelDeclaredAs("PROVIDER_NOT_KEPT");
const undeclaredModel = modelDeclaredAs("UNDECLARED");

const keptModel: AvailableLlmModel = {
	dataHandlingTier: "PROVIDER_KEPT",
	id: 11,
	scope: "WORKSPACE",
	displayName: "Team gateway",
	connectionDisplayName: "Own provider",
	pricingMode: "UNPRICED",
	supportsReasoning: true,
};

const models = [...mockAvailableModels, keptModel];

function binding(
	tier: AgentBinding["dataHandlingTier"],
	model: AvailableLlmModel,
	overrides: Partial<AgentBinding> = {},
): AgentBinding {
	return {
		purpose: "PRACTICE_REVIEW",
		dataHandlingTier: tier,
		instanceModelId: model.scope === "SHARED" ? model.id : undefined,
		workspaceModelId: model.scope === "WORKSPACE" ? model.id : undefined,
		enabled: true,
		ready: true,
		timeoutSeconds: 600,
		maxConcurrentJobs: 3,
		allowInternet: false,
		...overrides,
	};
}

const partiallyCovered: AgentBinding[] = [
	binding("IN_HOUSE", inHouseModel),
	binding("PROVIDER_KEPT", keptModel, { ready: false }),
	binding("UNDECLARED", undeclaredModel),
];

/**
 * One row per data-handling tier and purpose, because a member's AI choice is a ceiling and the
 * server picks the loosest ready row within it. The preview at the top of each card runs the same
 * rule client-side so an owner sees who gets which model before anyone asks.
 *
 * Rejected: one picker per purpose with a page-level tier filter — it hid the rows an owner was not
 * looking at, and a member's answer spans several of them.
 */
const meta = {
	component: AgentBindingsPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "acme",
		bindings: [binding("IN_HOUSE", inHouseModel)],
		availableModels: models,
		practicesEnabled: true,
		mentorEnabled: true,
		aiChoiceRequired: false,
		isLoading: false,
		isError: false,
		loadError: null,
		pendingTargets: new Set<string>(),
		onRetry: fn(),
		onSave: fn(),
		onTurnOff: fn(),
	},
} satisfies Meta<typeof AgentBindingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const TiersPartiallyCovered: Story = {
	args: { bindings: partiallyCovered },
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");

		await expect(previewRow(reviews, "Members who chose Only in-house")).toHaveTextContent(
			"→ Stays in-house: Local Llama (self-hosted)",
		);
		await expect(
			previewRow(reviews, "Members who chose Allow providers that keep nothing"),
		).toHaveTextContent("→ Stays in-house: Local Llama (self-hosted)");
		await expect(
			previewRow(reviews, "Members who chose Allow storage for safety checks"),
		).toHaveTextContent("→ Stays in-house: Local Llama (self-hosted)");
		await expect(previewRow(reviews, UNCHOSEN_ROW)).toHaveTextContent(
			"→ Not declared: My OpenAI key",
		);

		await expect(row(reviews, "Stays in-house").getByText("Ready")).toBeVisible();
		await expect(
			row(reviews, "Provider, kept for safety checks").getByText("Not ready"),
		).toBeVisible();
		await expect(row(reviews, "Provider, nothing kept").queryByText(/ready/i)).toBeNull();

		await userEvent.click(
			row(reviews, "Provider, nothing kept").getByRole("combobox", {
				name: /Provider, nothing kept/,
			}),
		);
		await expect(await screen.findByRole("option", { name: /GPT-5/ })).toBeVisible();
		await expect(screen.queryByRole("option", { name: /Local Llama/ })).toBeNull();
		await expect(screen.queryByRole("option", { name: /My OpenAI key/ })).toBeNull();
	},
};

export const NothingCovered: Story = {
	args: { bindings: [binding("UNDECLARED", undeclaredModel)] },
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");
		for (const term of [
			"Members who chose Only in-house",
			"Members who chose Allow providers that keep nothing",
			"Members who chose Allow storage for safety checks",
		]) {
			await expect(previewRow(reviews, term)).toHaveTextContent("→ nothing runs for them");
		}
		await expect(previewRow(reviews, UNCHOSEN_ROW)).toHaveTextContent(
			"→ Not declared: My OpenAI key",
		);
	},
};

/** Once the workspace requires the choice, the undeclared row serves nobody and the preview stops listing it. */
export const ChoiceRequired: Story = {
	args: { bindings: partiallyCovered, aiChoiceRequired: true },
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");
		await expect(reviews.queryByText(UNCHOSEN_ROW, { selector: "dt" })).toBeNull();
		await expect(row(reviews, UNCHOSEN_ROW).getByText(/serves no one now/)).toBeVisible();
	},
};

/** The route words the refusal from the registry; the row only shows what it is handed. */
export const SlotRejected: Story = {
	args: {
		bindings: partiallyCovered,
		saveErrors: {
			[bindingTargetKey({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" })]:
				"This model is declared as Provider, nothing kept; assign it to that row.",
		},
	},
	play: async ({ canvas, args }) => {
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "Stays in-house");
		await userEvent.click(inHouse.getByRole("button", { name: /^Save assignment/ }));
		await expect(args.onSave).toHaveBeenCalledWith(
			{ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
			expect.objectContaining({ instanceModelId: 2 }),
		);
		const picker = inHouse.getByRole("combobox", { name: /Stays in-house/ });
		await expect(picker).toHaveAttribute("aria-invalid", "true");
		await expect(inHouse.getByRole("alert")).toHaveTextContent(
			"This model is declared as Provider, nothing kept; assign it to that row.",
		);
		await expect(picker).toHaveAccessibleDescription(
			/This model is declared as Provider, nothing kept; assign it to that row\./,
		);
	},
};

/** A bound model whose facts were re-declared keeps its name on the row, which says why it stopped. */
export const BoundModelMoved: Story = {
	args: {
		bindings: [binding("IN_HOUSE", notKeptModel, { ready: false })],
	},
	play: async ({ canvas }) => {
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "Stays in-house");
		await expect(inHouse.getByText("Not ready")).toBeVisible();
		const picker = inHouse.getByRole("combobox", { name: /Stays in-house/ });
		await expect(picker).toHaveTextContent("GPT-5");
		await expect(picker).toHaveAccessibleDescription(
			"GPT-5 is now declared as Provider, nothing kept and no longer serves this row. Choose another model, or clear the assignment.",
		);
	},
};

/** With no other model of the row's tier on offer, the picker has nothing to choose, so the hint says so. */
export const BoundModelMovedNoAlternative: Story = {
	args: {
		bindings: [binding("IN_HOUSE", notKeptModel, { ready: false })],
		availableModels: [notKeptModel],
	},
	play: async ({ canvas }) => {
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "Stays in-house");
		const picker = inHouse.getByRole("combobox", { name: /Stays in-house/ });
		await expect(picker).toBeDisabled();
		await expect(picker).toHaveAccessibleDescription(
			"GPT-5 is now declared as Provider, nothing kept and no longer serves this row. Clear the assignment, or ask your host for a model declared as Stays in-house.",
		);
		await expect(inHouse.getByRole("button", { name: /^Clear assignment/ })).toBeEnabled();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
};

export const NoModelsAvailable: Story = {
	args: { bindings: [], availableModels: [] },
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");
		await expect(
			row(reviews, "Provider, nothing kept").getByText(/No model declared as/),
		).toHaveTextContent(
			"No model declared as Provider, nothing kept is available here yet. Ask your host, or add one under your own providers.",
		);
		await expect(row(reviews, UNCHOSEN_ROW).getByText(/No models are available yet/)).toBeVisible();
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

/** A switched-off purpose runs for nobody, however its rows are bound: the preview says so, row by row. */
export const ProjectReviewsDisabled: Story = {
	args: { bindings: partiallyCovered, practicesEnabled: false },
	play: async ({ canvas }) => {
		const card = purposeCard(canvas, "Practice reviews");
		await expect(card.getByText("Practice reviews off")).toBeVisible();
		for (const term of [
			"Members who chose Only in-house",
			"Members who chose Allow providers that keep nothing",
			"Members who chose Allow storage for safety checks",
			UNCHOSEN_ROW,
		]) {
			await expect(previewRow(card, term)).toHaveTextContent(
				"→ nothing runs for them (Practice reviews off)",
			);
		}
		// Heph is on but unbound here: nothing runs, and no switch is to blame.
		await expect(
			previewRow(purposeCard(canvas, "Heph"), "Members who chose Only in-house"),
		).toHaveTextContent(/^→ nothing runs for them$/);
		await expect(card.getByRole("link", { name: "Open Review: When and where" })).toHaveAttribute(
			"href",
			"/w/acme/admin/practices/review?section=when-and-where",
		);
		await expect(
			row(card, "Stays in-house").getByRole("button", { name: /^Save assignment/ }),
		).toBeEnabled();
	},
};

export const OnlyThePendingRowIsFrozen: Story = {
	args: {
		bindings: partiallyCovered,
		pendingTargets: new Set([bindingTargetKey({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" })]),
	},
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");
		await expect(
			row(reviews, "Stays in-house").getByRole("button", { name: /^Save assignment/ }),
		).toBeDisabled();
		await expect(
			row(reviews, "Provider, kept for safety checks").getByRole("button", {
				name: /^Save assignment/,
			}),
		).toBeEnabled();
		await expect(
			row(purposeCard(canvas, "Heph"), "Stays in-house").getByRole("button", {
				name: /^Save assignment/,
			}),
		).toBeEnabled();
	},
};

export const AdvancedDisclosure: Story = {
	play: async ({ canvas }) => {
		const reviews = row(purposeCard(canvas, "Practice reviews"), "Stays in-house");
		await openAdvanced(reviews);
		await expect(reviews.getByLabelText(/^Max concurrent runs/)).toHaveValue(3);
		const mentor = row(purposeCard(canvas, "Heph"), "Stays in-house");
		await openAdvanced(mentor);
		await expect(mentor.queryByLabelText(/^Max concurrent runs/)).toBeNull();
		await expect(mentor.getByLabelText(/^Timeout \(seconds\)/)).toHaveValue(10800);
	},
};

export const InvalidRunLimit: Story = {
	play: async ({ canvas }) => {
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "Stays in-house");
		await openAdvanced(inHouse);

		await userEvent.clear(inHouse.getByLabelText(/^Timeout \(seconds\)/));
		await userEvent.click(inHouse.getByRole("button", { name: /^Save assignment/ }));

		await expect(await canvas.findByText("Enter a number of seconds.")).toBeVisible();
		await expect(screen.queryByRole("status")).toBeNull();
	},
};

export const MobileReflow: Story = {
	args: { bindings: partiallyCovered },
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("Practice reviews");
		await expectNoPageOverflow();
		const save = row(purposeCard(canvas, "Practice reviews"), "Stays in-house").getByRole(
			"button",
			{ name: /^Save assignment/ },
		);
		// Vertical page scrolling is expected; saving must not need horizontal scrolling.
		save.scrollIntoView({ block: "center" });
		await expectControlOnScreen(save);
	},
};

export const Dark: Story = {
	args: { bindings: partiallyCovered },
	globals: { theme: "dark" },
};
