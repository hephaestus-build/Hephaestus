import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { AgentBinding, AvailableLlmModel, PracticePrecomputeSummary } from "@/api/types.gen";
import { DATA_HANDLING_DEFS } from "@/components/practice-vocabulary/data-handling-defs";
import { PRECOMPUTE_REQUIRED_DEF } from "@/components/practice-vocabulary/precompute-need-defs";
import {
	PURPOSE_STATUS_DEFS,
	type PurposeStatus,
} from "@/components/practice-vocabulary/purpose-status-defs";
import { withStandardPage } from "@/stories/decorators";
import { expectControlOnScreen, expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";
import { PROVIDER_PANEL_ID } from "./ModelPicker";
import { WorkspaceLlmProviderPanel } from "./WorkspaceLlmProviderPanel";

import {
	AgentBindingsPage,
	bindingTargetKey,
	type PrecomputeSummariesState,
} from "./AgentBindingsPage";
import {
	mockAvailableModels,
	mockCommentQualityNeeds,
	mockDecisionModel,
	mockDescribeWhatAndWhyNeeds,
	mockEmbeddingModel,
	mockPrecomputeModels,
	mockPrecomputeNeeds,
	SERVED_ALONE,
} from "./fixtures";

type Scope = StoryContext["canvas"];

const UNCHOSEN_ROW = "Members who have not chosen";

/** A purpose's row is the accordion trigger its title names. */
function trigger(canvas: Scope, title: string): HTMLElement {
	return canvas.getByRole("button", { name: title });
}

/** Opens the purpose's row if it is closed, and returns what it opened. */
async function openPurpose(canvas: Scope, title: string): Promise<Scope> {
	const button = trigger(canvas, title);
	if (button.getAttribute("aria-expanded") !== "true") {
		await userEvent.click(button);
	}
	return within(await canvas.findByRole("region", { name: title }));
}

/** A tier row is the group its heading names: the tier's label, or the unchosen row's own title. */
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

/**
 * A binding as the server sends it, `servedTiers` included: the page reads routing from the wire and
 * never derives it, so each fixture states it.
 */
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
		maxConcurrentJobs: 3,
		allowInternet: false,
		servedTiers: live ? [...SERVED_ALONE[tier]] : [],
		...overrides,
	};
}

const partiallyCovered: AgentBinding[] = [
	binding("IN_HOUSE", inHouseModel),
	binding("CLOUD", cloudModel, { ready: false }),
	binding("UNDECLARED", undeclaredModel),
];

/** Practice reviews and Heph serve every member on one In-house model; no precompute model is bound. */
const everyoneCovered: AgentBinding[] = [
	binding("IN_HOUSE", inHouseModel),
	binding("IN_HOUSE", inHouseModel, { purpose: "MENTOR" }),
];

/** Each tier has its own review model, so a Cloud script model serves Cloud members. */
const reviewsOnBothTiers: AgentBinding[] = [
	binding("IN_HOUSE", inHouseModel, { servedTiers: ["IN_HOUSE"] }),
	binding("IN_HOUSE", inHouseModel, { purpose: "MENTOR" }),
	binding("CLOUD", cloudModel),
];

const decisionForCloud = binding("CLOUD", mockDecisionModel, { purpose: "PRACTICE_DECISION" });

/** The same assignment where Cloud members' reviews run In-house: the server routes no one to it. */
const decisionForCloudUnserved: AgentBinding = { ...decisionForCloud, servedTiers: [] };

/** A page in use: every kind of cell, a fallback, an unmet need, and a quiet row. */
const workspaceInUse: AgentBinding[] = [
	...reviewsOnBothTiers,
	decisionForCloud,
	binding("IN_HOUSE", mockEmbeddingModel, { purpose: "PRACTICE_EMBEDDING" }),
];

const needsIn = (summaries: PracticePrecomputeSummary[]): PrecomputeSummariesState => ({
	status: "ready",
	summaries,
});

const statusLabel = (status: PurposeStatus) => PURPOSE_STATUS_DEFS[status].label;

/** No row may draw any status badge; the words stay only in the description a screen reader hears. */
async function expectNoStatusBadge(canvas: Scope) {
	for (const status of Object.keys(PURPOSE_STATUS_DEFS).filter(isPurposeStatus)) {
		await expect(canvas.queryByText(statusLabel(status), { ignore: ".sr-only" })).toBeNull();
	}
}

const isPurposeStatus = (value: string): value is PurposeStatus =>
	Object.hasOwn(PURPOSE_STATUS_DEFS, value);

/**
 * Two groups of purposes, each one row per purpose. Closed, a row is a line of a matrix: the kind,
 * the model each member answer gets, and a status only when something is off. Open, it holds the
 * tier editors, each with its own save. A row that asks something of the admin opens by itself,
 * once, when the page loads.
 *
 * Rejected: a table with a drawer per purpose. A drawer would hold three independent saves, and a
 * purpose-by-tier matrix scrolls sideways at 320 px; below `md` the cells stack instead.
 */
const meta = {
	component: AgentBindingsPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "acme",
		state: { status: "ready" },
		bindings: [binding("IN_HOUSE", inHouseModel)],
		availableModels: [...mockAvailableModels, ...mockPrecomputeModels],
		practicesEnabled: true,
		aiChoiceRequired: false,
		precomputeNeeds: needsIn([]),
		ownProviderAllowed: true,
		pendingWrites: new Map(),
		onFocusPurposeClosed: fn(),
		onSave: fn(),
		onTurnOff: fn(),
	},
} satisfies Meta<typeof AgentBindingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** The shape of both groups while the page loads, so nothing moves when the rows arrive. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Reviews and Heph" })).toBeVisible();
		await expect(
			canvas.getByRole("heading", { name: "Models for precompute scripts" }),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Practice reviews" })).toBeNull();
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

/**
 * Nothing asks for the admin: every row stays closed and no row draws a badge. A Cloud member's
 * cell names the In-house model that serves them, with its tier's icon.
 */
export const Quiet: Story = {
	args: { bindings: everyoneCovered, aiChoiceRequired: true },
	play: async ({ canvas }) => {
		for (const title of [
			"Practice reviews",
			"Heph",
			"Decision model",
			"Embedding model",
			"Reranking model",
		]) {
			await expect(trigger(canvas, title)).toHaveAttribute("aria-expanded", "false");
		}
		await expectNoStatusBadge(canvas);
		await expect(trigger(canvas, "Practice reviews")).toHaveAccessibleDescription(
			`In-house: ${inHouseModel.displayName} Cloud: ${inHouseModel.displayName} In-house`,
		);
		await expect(trigger(canvas, "Decision model")).toHaveAccessibleDescription(
			"No practice uses it In-house: none Cloud: none",
		);
	},
};

/** Some members get a model and some do not: one badge, and the cells show which. */
export const PartlySet: Story = {
	args: { bindings: [binding("CLOUD", cloudModel)], aiChoiceRequired: true },
	play: async ({ canvas }) => {
		const reviews = trigger(canvas, "Practice reviews");
		await expect(reviews).toHaveAccessibleDescription(
			`In-house: none Cloud: ${cloudModel.displayName} ${statusLabel("PARTLY_SET")}`,
		);
		await expect(
			within(reviews).getByText(statusLabel("PARTLY_SET"), { ignore: ".sr-only" }),
		).toBeVisible();
		await expect(reviews).toHaveAttribute("aria-expanded", "false");
	},
};

/**
 * A practice whose script cannot run without a model opens its row with a count and the members it
 * misses, above the forms. The practice leads *Used by*; an optional need only counts.
 */
export const RequiredNeedUnmet: Story = {
	args: {
		bindings: [...reviewsOnBothTiers, decisionForCloud],
		aiChoiceRequired: true,
		precomputeNeeds: needsIn(mockPrecomputeNeeds),
	},
	play: async ({ canvas }) => {
		const decision = trigger(canvas, "Decision model");
		await expect(decision).toHaveAttribute("aria-expanded", "true");
		await expect(decision).toHaveAccessibleDescription(
			`Used by 1 practice In-house: none Cloud: ${mockDecisionModel.displayName} ${statusLabel("NEEDS_ATTENTION")}`,
		);
		const region = within(canvas.getByRole("region", { name: "Decision model" }));
		// Static page content: opening the row does not announce it.
		await expect(region.queryByRole("alert")).toBeNull();
		const note = region.getByRole("note");
		await expect(note).toHaveTextContent(
			"1 practice needs a decision model for In-house members. Until you assign one, its precompute script does not run, and the review checks the practice without its help.",
		);
		// One state, one tone: the note wears the colour of the row's badge.
		const badge = within(decision).getByText(statusLabel("NEEDS_ATTENTION"), {
			ignore: ".sr-only",
		});
		await expect(getComputedStyle(note).color).toBe(getComputedStyle(badge).color);
		// Cloud members' reviews have their own Cloud model, so the Cloud script model serves them.
		await expect(row(region, "Cloud").queryByText(/reviews run on an In-house model/u)).toBeNull();
		const usedBy = within(region.getByRole("list", { name: "Used by" }));
		const practice = within(usedBy.getByRole("listitem"));
		await expect(
			levelsOpenedBy(practice.getByRole("link", { name: "Comment quality" })),
		).toStrictEqual(["practice:comment-quality"]);
		const required = practice.getByText(PRECOMPUTE_REQUIRED_DEF.label);
		await expect(required).toBeVisible();
		// The badge sits beside the name it qualifies, not at the far edge of the row.
		const name = practice.getByRole("link", { name: "Comment quality" }).getBoundingClientRect();
		await expect(required.getBoundingClientRect().left - name.right).toBeLessThan(40);
		await expect(practice.getByText("Not set for In-house members")).toBeVisible();
		await expect(region.getByText("As of each practice’s newest review.")).toBeVisible();

		// Used, unassigned and only optional: it counts its practices and stays closed.
		const embedding = trigger(canvas, "Embedding model");
		await expect(embedding).toHaveAttribute("aria-expanded", "false");
		await expect(embedding).toHaveAccessibleDescription(
			`Used by 2 practices In-house: none Cloud: none ${statusLabel("NOT_SET")}`,
		);
	},
};

const commentChecks: PracticePrecomputeSummary[] = ["Comment quality", "Comments explain why"].map(
	(practiceName, index) => ({
		...mockCommentQualityNeeds,
		practiceSlug: `comment-check-${index + 1}`,
		practiceName,
	}),
);

/** Several practices short of the same model share one warning that counts them; *Used by* names them. */
export const SeveralPracticesNeedIt: Story = {
	args: {
		bindings: [...reviewsOnBothTiers, decisionForCloud],
		aiChoiceRequired: true,
		precomputeNeeds: needsIn(commentChecks),
	},
	play: async ({ canvas }) => {
		const region = within(await canvas.findByRole("region", { name: "Decision model" }));
		const notes = region.getAllByRole("note");
		await expect(notes).toHaveLength(1);
		await expect(notes[0]).toHaveTextContent(
			"2 practices need a decision model for In-house members. Until you assign one, their precompute scripts do not run, and the reviews check those practices without their help.",
		);
		const names = within(region.getByRole("list", { name: "Used by" }))
			.getAllByRole("link")
			.map((link) => link.textContent);
		await expect(names).toStrictEqual(["Comment quality", "Comments explain why"]);
	},
};

/**
 * A script model is never looser than its review's. With no ready Cloud review model, Cloud members'
 * reviews run In-house, so the Cloud decision model serves nobody and its editor says why. The
 * overview still shows the assignment, muted as not used, and no practice uses the model, so the
 * row asks for nothing.
 */
export const CloudModelUnused: Story = {
	args: { bindings: [...everyoneCovered, decisionForCloudUnserved], aiChoiceRequired: true },
	play: async ({ canvas }) => {
		await expect(trigger(canvas, "Decision model")).toHaveAccessibleDescription(
			`No practice uses it In-house: none Cloud: ${mockDecisionModel.displayName} Not used`,
		);
		const cloud = row(await openPurpose(canvas, "Decision model"), "Cloud");
		const note =
			"Cloud members’ reviews run on an In-house model, so this model is not used. Assign a Cloud model for practice reviews to use it.";
		await expect(cloud.getByText(note)).toBeVisible();
		await expect(cloud.getByRole("combobox", { name: /Cloud/u })).toHaveAccessibleDescription(note);
		// Only the Cloud editor of a precompute kind has it.
		const inHouse = row(within(canvas.getByRole("region", { name: "Decision model" })), "In-house");
		await expect(inHouse.queryByText(/reviews run on an In-house model/u)).toBeNull();
	},
};

/**
 * The needs are read apart from the page: while they load, the precompute rows claim nothing about
 * them, and the rest of the page already works.
 */
export const PrecomputeNeedsLoading: Story = {
	args: { precomputeNeeds: { status: "loading" } },
	play: async ({ canvas }) => {
		// No count and no status: the description is the cells alone.
		await expect(trigger(canvas, "Decision model")).toHaveAccessibleDescription(
			"In-house: none Cloud: none Not chosen: none",
		);
		const reviews = await openPurpose(canvas, "Practice reviews");
		await expect(
			row(reviews, "In-house").getByRole("button", { name: /^Save assignment/u }),
		).toBeEnabled();
	},
};

/** A failed read of the needs says so in the precompute section, and nothing else stops. */
export const PrecomputeNeedsFailed: Story = {
	args: {
		precomputeNeeds: {
			status: "error",
			error: { status: 500, title: "Internal Server Error" },
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		const section = within(canvas.getByRole("region", { name: "Models for precompute scripts" }));
		await expect(
			section.getByText("We could not load which practices use these models"),
		).toBeVisible();
		await expect(canvas.queryByText("We could not load AI models")).toBeNull();
		await expect(trigger(canvas, "Decision model")).toHaveAccessibleDescription(
			"In-house: none Cloud: none Not chosen: none",
		);
		await expect(trigger(canvas, "Practice reviews")).toBeEnabled();
	},
};

const manyPractices: PracticePrecomputeSummary[] = Array.from({ length: 7 }, (_, index) => ({
	...mockDescribeWhatAndWhyNeeds,
	practiceSlug: `practice-${index + 1}`,
	practiceName: `Practice ${index + 1}`,
}));

/** A long list shows five practices, then the rest on request. An optional need wears no badge. */
export const ManyPracticesUseIt: Story = {
	args: { precomputeNeeds: needsIn(manyPractices) },
	play: async ({ canvas }) => {
		const region = await openPurpose(canvas, "Embedding model");
		const usedBy = within(region.getByRole("list", { name: "Used by" }));
		await expect(usedBy.getAllByRole("listitem")).toHaveLength(5);
		await expect(usedBy.queryByText("Optional")).toBeNull();
		await expect(usedBy.queryByText(PRECOMPUTE_REQUIRED_DEF.label)).toBeNull();
		await userEvent.click(region.getByRole("button", { name: "Show all 7" }));
		await expect(usedBy.getAllByRole("listitem")).toHaveLength(7);
		await expect(region.queryByRole("button", { name: /^Show all/u })).toBeNull();
	},
};

/** An assignment the server cannot run marks its purpose and opens it. */
export const TiersPartiallyCovered: Story = {
	args: { bindings: partiallyCovered },
	play: async ({ canvas }) => {
		const reviewsTrigger = trigger(canvas, "Practice reviews");
		await expect(reviewsTrigger).toHaveAttribute("aria-expanded", "true");
		// The Cloud model is not ready, so Cloud members fall back to the In-house one.
		await expect(reviewsTrigger).toHaveAccessibleDescription(
			`In-house: ${inHouseModel.displayName} Cloud: ${inHouseModel.displayName} In-house Not chosen: ${undeclaredModel.displayName} ${statusLabel("NEEDS_ATTENTION")}`,
		);
		const reviews = within(canvas.getByRole("region", { name: "Practice reviews" }));

		// Ready is the normal case and draws nothing; only the exception wears a badge.
		await expect(row(reviews, "In-house").queryByText(/ready/iu)).toBeNull();
		await expect(row(reviews, "Cloud").getByText("Not ready")).toBeVisible();

		await userEvent.click(row(reviews, "Cloud").getByRole("combobox", { name: /Cloud/u }));
		await expect(await screen.findByRole("option", { name: /^GPT-5,/u })).toBeVisible();
		await expect(screen.queryByRole("option", { name: /Local Llama/u })).toBeNull();
		await expect(screen.queryByRole("option", { name: /My OpenAI key/u })).toBeNull();
	},
};

/**
 * Only members who have not chosen get a model, so the row is partly set. Their editor names them
 * and wears no tier badge: *Not declared* is a model's tier, not an answer a member gave.
 */
export const NothingCovered: Story = {
	args: { bindings: [binding("UNDECLARED", undeclaredModel)] },
	play: async ({ canvas }) => {
		await expect(trigger(canvas, "Practice reviews")).toHaveAccessibleDescription(
			`In-house: none Cloud: none Not chosen: ${undeclaredModel.displayName} ${statusLabel("PARTLY_SET")}`,
		);
		const unchosen = row(await openPurpose(canvas, "Practice reviews"), UNCHOSEN_ROW);
		await expect(unchosen.getByRole("heading", { level: 4 })).toHaveTextContent(UNCHOSEN_ROW);
		await expect(unchosen.queryByText(DATA_HANDLING_DEFS.UNDECLARED.label)).toBeNull();
	},
};

/** Once the workspace requires the choice, the undeclared row serves nobody and loses its column. */
export const ChoiceRequired: Story = {
	args: { bindings: partiallyCovered, aiChoiceRequired: true },
	play: async ({ canvas }) => {
		await expect(trigger(canvas, "Practice reviews")).not.toHaveAccessibleDescription(
			/Not chosen/u,
		);
		await expect(canvas.queryByText("Not chosen")).toBeNull();
		const reviews = await openPurpose(canvas, "Practice reviews");
		// It still holds an assignment, so its editor stays to clear it.
		await expect(row(reviews, UNCHOSEN_ROW).getByText(/no one uses it now/u)).toBeVisible();
		// A purpose with nothing assigned there has no editor that would serve no one.
		const heph = await openPurpose(canvas, "Heph");
		await expect(heph.queryByRole("group", { name: UNCHOSEN_ROW })).toBeNull();
	},
};

/**
 * The route words the refusal from the registry; the row shows it and keeps what the admin chose.
 * The refusal is about that model, so choosing another takes it away.
 */
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

/** A row offers only the models that the server says can serve its purpose. */
export const OffersOnlyModelsThatServeThePurpose: Story = {
	play: async ({ canvas }) => {
		const embeddings = row(await openPurpose(canvas, "Embedding model"), UNCHOSEN_ROW);
		await userEvent.click(
			embeddings.getByRole("combobox", { name: new RegExp(UNCHOSEN_ROW, "u") }),
		);
		await expect(await screen.findByRole("option", { name: /Text embeddings/u })).toBeVisible();
		await expect(screen.queryByRole("option", { name: /^GPT-5,/u })).toBeNull();
		await expect(screen.queryByRole("option", { name: /Local Llama/u })).toBeNull();
	},
};

/**
 * With no review model, the row opens by itself: nothing reviews until one is assigned. A precompute
 * kind with no model at all names the API to connect and links to the providers below.
 */
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
			row(reviews, "Cloud").getByText(/No model for this purpose declared as/u),
		).toHaveTextContent(
			"No model for this purpose declared as Cloud is available yet. Ask an instance admin, or add one to a provider under Your providers.",
		);
		await expect(
			row(reviews, UNCHOSEN_ROW).getByText(/No model for this purpose is available yet/u),
		).toBeVisible();
		// A precompute kind with no model at all says which API to connect, once per row.
		const embedding = row(await openPurpose(canvas, "Embedding model"), "Cloud");
		await expect(embedding.getByRole("combobox", { name: /Cloud/u })).toHaveAccessibleDescription(
			"No embedding model yet. Connect a provider with the Embeddings API under Your providers, then add its model.",
		);
		await expect(embedding.queryByText(/No model for this purpose/u)).toBeNull();
		await userEvent.click(embedding.getByRole("link", { name: "Your providers" }));
		// Focus lands on the panel's wrapper, which holds the "Your providers" section.
		await expect(
			canvas.getByText("Connect your own provider").closest(`#${PROVIDER_PANEL_ID}`),
		).toHaveFocus();
	},
};

/** A workspace that may not add its own provider is sent to an instance admin, never to a dead end. */
export const NoModelsOwnProvidersBlocked: Story = {
	args: { bindings: [], availableModels: [], ownProviderAllowed: false },
	play: async ({ canvas }) => {
		const reviews = within(canvas.getByRole("region", { name: "Practice reviews" }));
		await expect(
			row(reviews, "Cloud").getByText(/No model for this purpose declared as/u),
		).toHaveTextContent(
			"No model for this purpose declared as Cloud is available yet. Ask an instance admin to share one.",
		);
		await expect(
			row(reviews, UNCHOSEN_ROW).getByText(/No model for this purpose is available yet/u),
		).toHaveTextContent(
			"No model for this purpose is available yet. Ask an instance admin to share one.",
		);
	},
};

/**
 * A switched-off purpose runs for nobody, however its rows are bound: each row says Off, the group
 * says why once, and no tier claims to be ready.
 */
export const ProjectReviewsDisabled: Story = {
	args: {
		bindings: [...partiallyCovered, decisionForCloudUnserved],
		practicesEnabled: false,
		precomputeNeeds: needsIn(mockPrecomputeNeeds),
	},
	play: async ({ canvas }) => {
		for (const title of ["Practice reviews", "Decision model", "Embedding model"]) {
			await expect(trigger(canvas, title)).toHaveAccessibleDescription(
				new RegExp(`${statusLabel("OFF")}$`, "u"),
			);
		}
		// Off outranks the unmet need, so nothing opens and nothing asks for a binding.
		await expect(trigger(canvas, "Decision model")).toHaveAttribute("aria-expanded", "false");
		await expect(trigger(canvas, "Practice reviews")).toHaveAttribute("aria-expanded", "false");
		await expect(
			canvas.getByText(/Practice reviews are off, so these models are not used\./u),
		).toBeVisible();
		for (const link of canvas.getAllByRole("link", { name: "Open Review: When and where" })) {
			await expect(link).toHaveAttribute(
				"href",
				"/w/acme/admin/practices/review?section=when-and-where",
			);
		}

		// While reviews are off nobody gets the bound models, so every cell is empty.
		await expect(trigger(canvas, "Practice reviews")).toHaveAccessibleDescription(
			`In-house: none Cloud: none Not chosen: none ${statusLabel("OFF")}`,
		);
		const reviews = await openPurpose(canvas, "Practice reviews");
		await expect(reviews.queryByText("Not ready")).toBeNull();
		await expect(reviews.queryByRole("alert")).toBeNull();
		await expect(
			row(reviews, "In-house").getByRole("button", { name: /^Save assignment/u }),
		).toBeEnabled();
		// Heph is on but unassigned here: nothing runs, and no switch is to blame.
		await expect(trigger(canvas, "Heph")).toHaveAccessibleDescription(
			`In-house: none Cloud: none Not chosen: none ${statusLabel("NOT_SET")}`,
		);
	},
};

/** The row being saved says so on its own button; every other row stays editable. */
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
		const heph = await openPurpose(canvas, "Heph");
		await expect(
			row(heph, "In-house").getByRole("button", { name: /^Save assignment/u }),
		).toBeEnabled();
	},
};

/** The row being cleared says so on its own button, and its save waits too. */
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

/** A fix link elsewhere names a purpose: its row opens and comes into view, and closing it says so. */
export const DeepLinkedPurpose: Story = {
	args: { bindings: everyoneCovered, focusPurpose: "PRACTICE_RERANKING" },
	play: async ({ canvas, args }) => {
		const reranking = trigger(canvas, "Reranking model");
		await expect(reranking).toHaveAttribute("aria-expanded", "true");
		await expect(trigger(canvas, "Decision model")).toHaveAttribute("aria-expanded", "false");
		await waitFor(async () => {
			const { top } = reranking.getBoundingClientRect();
			await expect(top).toBeLessThan(window.innerHeight);
			await expect(top).toBeGreaterThanOrEqual(0);
		});
		await userEvent.click(reranking);
		await expect(args.onFocusPurposeClosed).toHaveBeenCalledOnce();
	},
};

export const AdvancedDisclosure: Story = {
	play: async ({ canvas }) => {
		const reviews = row(await openPurpose(canvas, "Practice reviews"), "In-house");
		await openAdvanced(reviews);
		await expect(reviews.getByLabelText(/^Max concurrent runs/u)).toHaveValue(3);
		await expect(reviews.queryByRole("switch", { name: /^Internet access/u })).toBeNull();
		const mentor = row(await openPurpose(canvas, "Heph"), "In-house");
		await openAdvanced(mentor);
		await expect(mentor.queryByLabelText(/^Max concurrent runs/u)).toBeNull();
		await expect(mentor.getByLabelText(/^Timeout \(seconds\)/u)).toHaveValue(10_800);
		// A precompute model runs under its practice review's limits and has none of its own.
		await expect(
			row(await openPurpose(canvas, "Embedding model"), "In-house").queryByRole("button", {
				name: /^Advanced/u,
			}),
		).toBeNull();
	},
};

export const HephAdvancedOffersInternetAccess: Story = {
	play: async ({ canvas }) => {
		const mentor = row(await openPurpose(canvas, "Heph"), "In-house");
		await openAdvanced(mentor);
		await expect(mentor.getByRole("switch", { name: /^Internet access/u })).not.toBeChecked();
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
	args: {
		bindings: workspaceInUse,
		precomputeNeeds: needsIn([mockCommentQualityNeeds]),
	},
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async ({ canvas }) => {
		await canvas.findByRole("region", { name: "Decision model" });
		await expectNoPageOverflow();
		const save = row(
			within(canvas.getByRole("region", { name: "Decision model" })),
			"In-house",
		).getByRole("button", { name: /^Save assignment/u });
		// Vertical page scrolling is expected; saving must not need horizontal scrolling.
		save.scrollIntoView({ block: "center" });
		await expectControlOnScreen(save);
	},
};

/** The page in use, in light: the counterpart of `Dark`. */
export const InUse: Story = {
	args: {
		bindings: workspaceInUse,
		precomputeNeeds: needsIn(mockPrecomputeNeeds),
	},
	play: async ({ canvas }) => {
		// Heph is a kind like the others here, so its mark takes the review kind's neutral tone, not
		// the mentor accent.
		const glyphTone = (title: string) => {
			// The row's first glyph is its kind mark, which is hidden from the accessibility tree.
			const glyph = trigger(canvas, title).querySelector("svg");
			if (glyph === null) {
				throw new Error(`The ${title} row draws no kind mark`);
			}
			return getComputedStyle(glyph).color;
		};
		await expect(glyphTone("Heph")).toBe(glyphTone("Practice reviews"));
		await expect(trigger(canvas, "Heph")).toHaveAccessibleDescription(
			`In-house: ${inHouseModel.displayName} Cloud: ${inHouseModel.displayName} In-house Not chosen: none ${statusLabel("PARTLY_SET")}`,
		);
		await expect(trigger(canvas, "Embedding model")).toHaveAccessibleDescription(
			`Used by 2 practices In-house: ${mockEmbeddingModel.displayName} Cloud: ${mockEmbeddingModel.displayName} In-house Not chosen: none ${statusLabel("PARTLY_SET")}`,
		);
		await expect(trigger(canvas, "Reranking model")).toHaveAccessibleDescription(
			"No practice uses it In-house: none Cloud: none Not chosen: none",
		);
	},
};

export const Dark: Story = {
	args: {
		bindings: workspaceInUse,
		precomputeNeeds: needsIn(mockPrecomputeNeeds),
	},
	globals: { theme: "dark" },
};
