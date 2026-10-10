import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { AvailableLlmModel } from "@/api/types.gen";
import { Label } from "@/components/ui/label";

import { ModelPicker, type ModelPickerProps } from "./ModelPicker";

const models: AvailableLlmModel[] = [
	{
		dataHandlingTier: "CLOUD",
		id: 1,
		scope: "SHARED",
		displayName: "GPT-5",
		connectionDisplayName: "Organization endpoint",
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
		pricingMode: "PRICED",
		per1mInputUsd: 1,
		per1mOutputUsd: 2,
		reasoningEffort: "MEDIUM",
	},
	{
		dataHandlingTier: "IN_HOUSE",
		id: 2,
		scope: "WORKSPACE",
		displayName: "GPT-5",
		connectionDisplayName: "Workspace endpoint",
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
		pricingMode: "NO_CHARGE",
	},
];

/** Wires a real label, because the picker names its popup listbox from it. */
function renderPicker(
	props: Omit<ModelPickerProps, "id" | "aria-labelledby" | "ownProviderAllowed">,
) {
	return render(
		<>
			<Label id="model-label" htmlFor="model">
				Model
			</Label>
			<ModelPicker id="model" aria-labelledby="model-label" ownProviderAllowed {...props} />
		</>,
	);
}

describe("ModelPicker", () => {
	it("names the chosen model on the trigger, and tells duplicate names apart by connection in the list", () => {
		renderPicker({
			availableModels: models,
			value: { scope: "SHARED", id: 1 },
			onChange: vi.fn(),
		});
		// The model's own name only: the connection is the option's second line.
		expect(screen.getByRole("combobox").textContent).toMatch(/^GPT-5\W?$/u);
		fireEvent.click(screen.getByRole("combobox"));
		screen.getByRole("option", { name: /^GPT-5, Organization endpoint,/u });
		screen.getByRole("option", { name: /^GPT-5, Workspace endpoint,/u });
		// Grouped by whose money each model spends, in the usage page's words.
		screen.getByText("Shared models");
		screen.getByText("Own provider");
	});

	// Names written out rather than composed through `priceLabel` and the registry, the helpers the
	// component itself calls: a composed expectation catches "the price is gone" and never "the price
	// is wrong".
	it("keeps the data handling and the price in each option's accessible name", () => {
		renderPicker({ availableModels: models, value: null, onChange: vi.fn() });
		fireEvent.click(screen.getByRole("combobox"));

		screen.getByRole("option", {
			name: "GPT-5, Organization endpoint, Cloud, $1.00 input · $2.00 output / 1M tokens",
		});
		screen.getByRole("option", {
			name: "GPT-5, Workspace endpoint, In-house, No metered API cost",
		});
	});

	it("lists only the models declared as the requested tier", () => {
		renderPicker({
			availableModels: models,
			value: null,
			onChange: vi.fn(),
			tier: "IN_HOUSE",
		});
		fireEvent.click(screen.getByRole("combobox"));

		screen.getByRole("option", { name: /Workspace endpoint/u });
		expect(screen.queryByRole("option", { name: /Organization endpoint/u })).toBeNull();
		expect(screen.queryByText("Shared models")).toBeNull();
	});

	it("disables itself when the tier filter leaves nothing to list", () => {
		renderPicker({
			availableModels: models.filter((model) => model.dataHandlingTier === "CLOUD"),
			value: null,
			onChange: vi.fn(),
			tier: "IN_HOUSE",
		});
		expect(screen.getByRole("combobox").hasAttribute("disabled")).toBe(true);
	});

	it("still names a selected model the tier filter no longer offers", () => {
		renderPicker({
			availableModels: models,
			value: { scope: "SHARED", id: 1 },
			onChange: vi.fn(),
			tier: "IN_HOUSE",
		});
		expect(screen.getByRole("combobox").textContent).toMatch(/^GPT-5\W?$/u);
	});

	it("marks the trigger invalid and links its description when asked to", () => {
		renderPicker({
			availableModels: models,
			value: null,
			onChange: vi.fn(),
			invalid: true,
			"aria-describedby": "picker-hint",
		});

		const trigger = screen.getByRole("combobox");
		expect(trigger.getAttribute("aria-invalid")).toBe("true");
		expect(trigger.getAttribute("aria-describedby")).toContain("picker-hint");
	});

	it("warns about a reasoning decision model, and not about one asked for no reasoning", () => {
		const decision: AvailableLlmModel = {
			dataHandlingTier: "CLOUD",
			id: 9,
			scope: "SHARED",
			displayName: "Decider",
			connectionDisplayName: "Decisions endpoint",
			purposes: ["PRACTICE_DECISION"],
			pricingMode: "NO_CHARGE",
			reasoningEffort: "NONE",
		};
		const { rerender } = renderPicker({
			availableModels: [decision],
			value: { scope: "SHARED", id: 9 },
			onChange: vi.fn(),
			purpose: "PRACTICE_DECISION",
		});
		expect(screen.getByRole("combobox").getAttribute("aria-describedby")).toBeNull();
		expect(screen.queryByText(/reasons before it answers/u)).toBeNull();

		rerender(
			<>
				<Label id="model-label" htmlFor="model">
					Model
				</Label>
				<ModelPicker
					id="model"
					aria-labelledby="model-label"
					availableModels={[{ ...decision, reasoningEffort: "LOW" }]}
					value={{ scope: "SHARED", id: 9 }}
					onChange={vi.fn()}
					purpose="PRACTICE_DECISION"
					ownProviderAllowed
				/>
			</>,
		);
		const hint = screen.getByText(/reasons before it answers/u);
		expect(screen.getByRole("combobox").getAttribute("aria-describedby")).toBe(hint.id);
	});
});
