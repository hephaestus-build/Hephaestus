import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { AvailableLlmModel } from "@/api/types.gen";
import { Label } from "@/components/ui/label";

import { ModelPicker, type ModelPickerProps } from "./ModelPicker";

const models: AvailableLlmModel[] = [
	{
		dataHandlingTier: "PROVIDER_NOT_KEPT",
		id: 1,
		scope: "SHARED",
		displayName: "GPT-5",
		connectionDisplayName: "Organization endpoint",
		pricingMode: "PRICED",
		per1mInputUsd: 1,
		per1mOutputUsd: 2,
		supportsReasoning: true,
	},
	{
		dataHandlingTier: "IN_HOUSE",
		id: 2,
		scope: "WORKSPACE",
		displayName: "GPT-5",
		connectionDisplayName: "Workspace endpoint",
		pricingMode: "NO_CHARGE",
		supportsReasoning: false,
	},
];

/** Wires a real label, because the picker names its popup listbox from it. */
function renderPicker(props: Omit<ModelPickerProps, "id" | "aria-labelledby">) {
	return render(
		<>
			<Label id="model-label" htmlFor="model">
				Model
			</Label>
			<ModelPicker id="model" aria-labelledby="model-label" {...props} />
		</>,
	);
}

describe("ModelPicker", () => {
	it("distinguishes duplicate model names by connection in the selection and options", () => {
		renderPicker({ availableModels: models, value: { scope: "SHARED", id: 1 }, onChange: vi.fn() });
		expect(screen.getByRole("combobox").textContent).toContain("GPT-5 · Organization endpoint");
		fireEvent.click(screen.getByRole("combobox"));
		screen.getByRole("option", { name: /GPT-5 · Organization endpoint/ });
		screen.getByRole("option", { name: /GPT-5 · Workspace endpoint/ });
		screen.getByText("Shared models");
		screen.getByText("Your models");
	});

	// Names written out rather than composed through `priceLabel` and the registry, the helpers the
	// component itself calls: a composed expectation catches "the price is gone" and never "the price
	// is wrong".
	it("keeps the data handling and the price in each option's accessible name", () => {
		renderPicker({ availableModels: models, value: null, onChange: vi.fn() });
		fireEvent.click(screen.getByRole("combobox"));

		screen.getByRole("option", {
			name: "GPT-5 · Organization endpoint · Provider, nothing kept · $1.00 input · $2.00 output / 1M tokens",
		});
		screen.getByRole("option", {
			name: "GPT-5 · Workspace endpoint · Stays in-house · No metered API cost",
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

		screen.getByRole("option", { name: /Workspace endpoint/ });
		expect(screen.queryByRole("option", { name: /Organization endpoint/ })).toBeNull();
		expect(screen.queryByText("Shared models")).toBeNull();
	});

	it("disables itself when the tier filter leaves nothing to list", () => {
		renderPicker({
			availableModels: models,
			value: null,
			onChange: vi.fn(),
			tier: "PROVIDER_KEPT",
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
		expect(screen.getByRole("combobox").textContent).toContain("GPT-5 · Organization endpoint");
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
});
