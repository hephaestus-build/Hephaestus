import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { AgentBinding, AvailableLlmModel } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectControlOnScreen, expectNoPageOverflow } from "@/stories/reflow";

import { AgentBindingsPage, bindingTargetKey } from "./AgentBindingsPage";
import { mockAvailableModels } from "./fixtures";

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
	if (!(dd instanceof HTMLElement)) {
		throw new Error(`No definition for ${term}`);
	}
	return dd;
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
	binding("CLOUD", cloudModel, { ready: false }),
	binding("UNDECLARED", undeclaredModel),
];

/**
 * One row per data-handling tier and purpose, because a member's AI choice is a ceiling and the
 * server picks the loosest ready row within it. The preview at the top of each card runs the same
 * rule client-side so an owner sees who gets which model before anyone asks.
 *
 * Rejected: one picker per purpose with a page-level tier filter. It hid the rows an owner was not
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
		availableModels: mockAvailableModels,
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

		await expect(previewRow(reviews, "In-house")).toHaveTextContent(
			"Local Llama (self-hosted) (In-house)",
		);
		await expect(previewRow(reviews, "Cloud")).toHaveTextContent(
			"Local Llama (self-hosted) (In-house)",
		);
		await expect(previewRow(reviews, UNCHOSEN_ROW)).toHaveTextContent(
			"My OpenAI key (Not declared)",
		);

		await expect(row(reviews, "In-house").getByText("Ready")).toBeVisible();
		await expect(row(reviews, "Cloud").getByText("Not ready")).toBeVisible();
		// Heph binds nothing here, so its rows carry no readiness at all.
		await expect(row(purposeCard(canvas, "Heph"), "In-house").queryByText(/ready/iu)).toBeNull();

		await userEvent.click(row(reviews, "Cloud").getByRole("combobox", { name: /Cloud/u }));
		await expect(await screen.findByRole("option", { name: /GPT-5/u })).toBeVisible();
		await expect(screen.queryByRole("option", { name: /Local Llama/u })).toBeNull();
		await expect(screen.queryByRole("option", { name: /My OpenAI key/u })).toBeNull();
	},
};

export const NothingCovered: Story = {
	args: { bindings: [binding("UNDECLARED", undeclaredModel)] },
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");
		for (const term of ["In-house", "Cloud"]) {
			await expect(previewRow(reviews, term)).toHaveTextContent("Nothing runs for them");
		}
		await expect(previewRow(reviews, UNCHOSEN_ROW)).toHaveTextContent(
			"My OpenAI key (Not declared)",
		);
	},
};

/** Once the workspace requires the choice, the undeclared row serves nobody and the preview stops listing it. */
export const ChoiceRequired: Story = {
	args: { bindings: partiallyCovered, aiChoiceRequired: true },
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");
		await expect(reviews.queryByText(UNCHOSEN_ROW, { selector: "dt" })).toBeNull();
		await expect(row(reviews, UNCHOSEN_ROW).getByText(/serves no one now/u)).toBeVisible();
	},
};

/** The route words the refusal from the registry; the row only shows what it is handed. */
export const SlotRejected: Story = {
	args: {
		bindings: partiallyCovered,
		saveErrors: {
			[bindingTargetKey({ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" })]:
				"This model is declared as Cloud. Assign it to that row.",
		},
	},
	play: async ({ canvas, args }) => {
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "In-house");
		await userEvent.click(inHouse.getByRole("button", { name: /^Save assignment/u }));
		await expect(args.onSave).toHaveBeenCalledWith(
			{ purpose: "PRACTICE_REVIEW", tier: "IN_HOUSE" },
			expect.objectContaining({ instanceModelId: 2 }),
		);
		const picker = inHouse.getByRole("combobox", { name: /In-house/u });
		await expect(picker).toHaveAttribute("aria-invalid", "true");
		await expect(inHouse.getByRole("alert")).toHaveTextContent(
			"This model is declared as Cloud. Assign it to that row.",
		);
		await expect(picker).toHaveAccessibleDescription(
			/This model is declared as Cloud\. Assign it to that row\./u,
		);
	},
};

/** A bound model that was re-declared keeps its name on the row, which says why it stopped. */
export const BoundModelMoved: Story = {
	args: {
		bindings: [binding("IN_HOUSE", cloudModel, { ready: false })],
	},
	play: async ({ canvas }) => {
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "In-house");
		await expect(inHouse.getByText("Not ready")).toBeVisible();
		const picker = inHouse.getByRole("combobox", { name: /In-house/u });
		await expect(picker).toHaveTextContent("GPT-5");
		await expect(picker).toHaveAccessibleDescription(
			"GPT-5 is now declared as Cloud and no longer serves this row. Choose another model, or clear the assignment.",
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
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "In-house");
		const picker = inHouse.getByRole("combobox", { name: /In-house/u });
		await expect(picker).toBeDisabled();
		await expect(picker).toHaveAccessibleDescription(
			"GPT-5 is now declared as Cloud and no longer serves this row. Clear the assignment, or ask your host for a model declared as In-house.",
		);
		await expect(inHouse.getByRole("button", { name: /^Clear assignment/u })).toBeEnabled();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
};

export const NoModelsAvailable: Story = {
	args: { bindings: [], availableModels: [] },
	play: async ({ canvas }) => {
		const reviews = purposeCard(canvas, "Practice reviews");
		await expect(row(reviews, "Cloud").getByText(/No model declared as/u)).toHaveTextContent(
			"No model declared as Cloud is available here yet. Ask your host, or add one under your own providers.",
		);
		await expect(
			row(reviews, UNCHOSEN_ROW).getByText(/No models are available yet/u),
		).toBeVisible();
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
		for (const term of ["In-house", "Cloud", UNCHOSEN_ROW]) {
			await expect(previewRow(card, term)).toHaveTextContent(
				"Nothing runs for them (Practice reviews off)",
			);
		}
		// Heph is on but unbound here: nothing runs, and no switch is to blame.
		await expect(previewRow(purposeCard(canvas, "Heph"), "In-house")).toHaveTextContent(
			/^Nothing runs for them$/u,
		);
		await expect(card.getByRole("link", { name: "Open Review: When and where" })).toHaveAttribute(
			"href",
			"/w/acme/admin/practices/review?section=when-and-where",
		);
		await expect(
			row(card, "In-house").getByRole("button", { name: /^Save assignment/u }),
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
			row(reviews, "In-house").getByRole("button", { name: /^Save assignment/u }),
		).toBeDisabled();
		await expect(
			row(reviews, "Cloud").getByRole("button", { name: /^Save assignment/u }),
		).toBeEnabled();
		await expect(
			row(purposeCard(canvas, "Heph"), "In-house").getByRole("button", {
				name: /^Save assignment/u,
			}),
		).toBeEnabled();
	},
};

export const AdvancedDisclosure: Story = {
	play: async ({ canvas }) => {
		const reviews = row(purposeCard(canvas, "Practice reviews"), "In-house");
		await openAdvanced(reviews);
		await expect(reviews.getByLabelText(/^Max concurrent runs/u)).toHaveValue(3);
		await expect(reviews.queryByRole("switch", { name: /^Internet access/u })).toBeNull();
		const mentor = row(purposeCard(canvas, "Heph"), "In-house");
		await openAdvanced(mentor);
		await expect(mentor.queryByLabelText(/^Max concurrent runs/u)).toBeNull();
		await expect(mentor.getByLabelText(/^Timeout \(seconds\)/u)).toHaveValue(10_800);
	},
};

export const HephAdvancedOffersInternetAccess: Story = {
	play: async ({ canvas }) => {
		const mentor = row(purposeCard(canvas, "Heph"), "In-house");
		await openAdvanced(mentor);
		await expect(mentor.getByRole("switch", { name: /^Internet access/u })).not.toBeChecked();
	},
};

export const InvalidRunLimit: Story = {
	play: async ({ canvas }) => {
		const inHouse = row(purposeCard(canvas, "Practice reviews"), "In-house");
		await openAdvanced(inHouse);

		await userEvent.clear(inHouse.getByLabelText(/^Timeout \(seconds\)/u));
		await userEvent.click(inHouse.getByRole("button", { name: /^Save assignment/u }));

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
		const save = row(purposeCard(canvas, "Practice reviews"), "In-house").getByRole("button", {
			name: /^Save assignment/u,
		});
		// Vertical page scrolling is expected; saving must not need horizontal scrolling.
		save.scrollIntoView({ block: "center" });
		await expectControlOnScreen(save);
	},
};

export const Dark: Story = {
	args: { bindings: partiallyCovered },
	globals: { theme: "dark" },
};
