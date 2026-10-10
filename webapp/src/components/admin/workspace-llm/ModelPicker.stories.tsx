import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { AvailableLlmModel } from "@/api/types.gen";
import { AGENT_PURPOSE_DEFS } from "@/components/practice-vocabulary/agent-purpose-defs";
import { Label } from "@/components/ui/label";
import { expectGenuinelyDisabled } from "@/test/controls";

import {
	mockAvailableModels,
	mockDecisionModel,
	mockReasoningDecisionModel,
	mockRerankModel,
} from "./fixtures";
import { ModelPicker } from "./ModelPicker";

/**
 * A model's declared data handling travels with its name: each option's second line holds its
 * connection and the tier's muted icon and name, as the tables and the assignments draw a tier, so
 * an admin choosing a model for a tier never has to remember what it was declared as. The closed
 * picker shows the model's mark and name only. The groups use the usage page's purse names. The
 * `tier` prop filters rather than disables the other models: a disabled option would still have to
 * be read past, and the assignment already says which tier it holds.
 */
const meta = {
	component: ModelPicker,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		id: "review-model",
		"aria-labelledby": "review-model-label",
		availableModels: mockAvailableModels,
		value: null,
		onChange: fn(),
		ownProviderAllowed: true,
	},
	decorators: [
		(Story, { args }) => (
			<div className="w-80">
				<Label id="review-model-label" htmlFor="review-model" className="mb-2">
					{args.purpose === undefined ? "Review model" : AGENT_PURPOSE_DEFS[args.purpose].title}
				</Label>
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ModelPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** Closed, the picker names the model alone, after its mark: the connection is in the list. */
export const SharedSelected: Story = {
	args: { value: { scope: "SHARED", id: 1 } },
	play: async ({ canvas }) => {
		const trigger = canvas.getByRole("combobox");
		await expect(trigger).toHaveTextContent(/^GPT-5\W?$/u);
		await expect(trigger).not.toHaveTextContent("OpenAI production");
	},
};

export const OwnProviderSelected: Story = {
	args: { value: { scope: "WORKSPACE", id: 10 } },
};

export const NoModelsYet: Story = {
	args: { availableModels: [] },
};

export const Invalid: Story = {
	args: { invalid: true, "aria-describedby": "model-picker-error" },
	render: (args) => (
		<div className="space-y-2">
			<ModelPicker {...args} />
			<p id="model-picker-error" className="text-sm text-destructive">
				Choose the model this runs on.
			</p>
		</div>
	),
};

export const OpensAndListsGroups: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox"));
		await expect(await screen.findByRole("option", { name: /^GPT-5, .*Cloud/u })).toBeVisible();
		await expect(
			await screen.findByRole("option", { name: /^My OpenAI key, .*Not declared/u }),
		).toBeVisible();
		await expect(screen.getByText("Shared models")).toBeVisible();
		await expect(screen.getByText("Own provider")).toBeVisible();
	},
};

export const FilteredToTier: Story = {
	args: { tier: "IN_HOUSE" },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox"));
		await expect(
			await screen.findByRole("option", { name: /^Local Llama.*, In-house/u }),
		).toBeVisible();
		await expect(screen.getAllByRole("option")).toHaveLength(1);
	},
};

/**
 * With nothing to list the picker disables itself; the row around it says what an empty list means
 * for that tier, in the shape shown here, because only the caller knows whether the reader can add
 * a model.
 */
export const NoModelsForTier: Story = {
	args: {
		tier: "CLOUD",
		availableModels: mockAvailableModels.filter((model) => model.dataHandlingTier !== "CLOUD"),
		"aria-describedby": "model-picker-empty",
	},
	render: (args) => (
		<div className="space-y-2">
			<ModelPicker {...args} />
			<p id="model-picker-empty" className="text-sm text-muted-foreground">
				No model declared as <span className="font-medium">Cloud</span> is available yet. Ask an
				instance admin, or add one to a provider under Your providers.
			</p>
		</div>
	),
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("combobox"));
		await expect(screen.queryByRole("option")).not.toBeInTheDocument();
	},
};

/**
 * A precompute kind with no model at all names the API that serves only it, and links to the
 * workspace's own providers on the same page.
 */
export const NoDecisionModelYet: Story = {
	args: { purpose: "PRACTICE_DECISION", availableModels: [] },
	play: async ({ canvas }) => {
		const picker = canvas.getByRole("combobox", { name: "Decision model" });
		await expectGenuinelyDisabled(picker);
		await expect(picker).toHaveAccessibleDescription(
			"No decision model yet. Connect a provider with the Decisions API under Your providers, then add its model.",
		);
		await expect(canvas.getByRole("link", { name: "Your providers" })).toHaveAttribute(
			"href",
			"#provider-panel",
		);
	},
};

/** An admin who may not add a provider cannot follow the connection step, so the hint names who can. */
export const NoEmbeddingModelOwnProvidersBlocked: Story = {
	args: { purpose: "PRACTICE_EMBEDDING", availableModels: [], ownProviderAllowed: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("combobox", { name: "Embedding model" }),
		).toHaveAccessibleDescription("No embedding model yet. Ask an instance admin to share one.");
	},
};

/** A decision needs an answer at once with token probabilities, so a reasoning model earns a warning. */
export const ReasoningDecisionModel: Story = {
	args: {
		purpose: "PRACTICE_DECISION",
		availableModels: [mockDecisionModel, mockReasoningDecisionModel],
		value: { scope: "SHARED", id: mockReasoningDecisionModel.id },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox")).toHaveAccessibleDescription(
			"This model reasons before it answers. Decisions need a model that answers at once with token probabilities.",
		);
	},
};

/** The same picker says nothing for a decision model that answers at once. */
export const DecisionModelAnswersAtOnce: Story = {
	args: {
		purpose: "PRACTICE_DECISION",
		availableModels: [mockDecisionModel, mockReasoningDecisionModel],
		value: { scope: "SHARED", id: mockDecisionModel.id },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox")).not.toHaveAccessibleDescription();
	},
};

/**
 * A reranker that may report no tokens counts as unpriced, which pauses reviews while their spend
 * has a monthly budget. Only an instance admin prices a shared model, so the hint sends the admin to one.
 */
export const UnpricedReranker: Story = {
	args: {
		purpose: "PRACTICE_RERANKING",
		availableModels: [mockRerankModel],
		value: { scope: "SHARED", id: mockRerankModel.id },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox")).toHaveAccessibleDescription(
			"If this provider reports no tokens, its calls count as unpriced, which pauses reviews while their spend has a monthly budget. Ask an instance admin to set No metered API cost for a self-hosted reranker.",
		);
	},
};

const ownRerankModel: AvailableLlmModel = { ...mockRerankModel, scope: "WORKSPACE", id: 31 };

/** The workspace prices its own model, on the model's row in its providers below. */
export const UnpricedOwnReranker: Story = {
	args: {
		purpose: "PRACTICE_RERANKING",
		availableModels: [ownRerankModel],
		value: { scope: "WORKSPACE", id: ownRerankModel.id },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox")).toHaveAccessibleDescription(
			"If this provider reports no tokens, its calls count as unpriced, which pauses reviews while their spend has a monthly cap. Set No metered API cost on this model below for a self-hosted reranker.",
		);
	},
};
