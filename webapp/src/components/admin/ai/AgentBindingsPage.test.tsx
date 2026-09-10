import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { AgentBinding, AvailableLlmModel } from "@/api/types.gen";

import { AgentBindingsPage, type AgentBindingsPageProps } from "./AgentBindingsPage";

const model: AvailableLlmModel = {
	processingLocation: "UNCLASSIFIED",
	id: 20,
	scope: "SHARED",
	displayName: "GPT Test",
	connectionDisplayName: "Shared OpenAI",
	supportsReasoning: false,
	pricingMode: "NO_CHARGE",
};

const detectionBinding: AgentBinding = {
	processingLocation: "UNCLASSIFIED",
	purpose: "PRACTICE_REVIEW",
	instanceModelId: 20,
	enabled: true,
	ready: false,
	timeoutSeconds: 600,
	maxConcurrentJobs: 1,
	allowInternet: false,
};

function renderPage(overrides: Partial<AgentBindingsPageProps> = {}) {
	const onSave = vi.fn();
	const onTurnOff = vi.fn();
	render(
		<AgentBindingsPage
			workspaceSlug="demo"
			bindings={[detectionBinding]}
			availableModels={[model]}
			practicesEnabled
			mentorEnabled
			isLoading={false}
			isError={false}
			loadError={null}
			pendingPurposes={new Set()}
			onRetry={vi.fn()}
			onSave={onSave}
			onTurnOff={onTurnOff}
			{...overrides}
		/>,
	);
	return { onSave, onTurnOff };
}

function practiceDetectionCard(): HTMLElement {
	const field = screen.getByLabelText("Practice reviews model");
	const form = field.closest("form");
	if (form == null) throw new Error("practice-review card has no form");
	return form;
}

describe("AgentBindingsPage", () => {
	it("renders an assignment card for each purpose", () => {
		renderPage();
		screen.getByText("Practice reviews");
		screen.getByText("Heph");

		const unassignedSwitch = within(screen.getByRole("region", { name: "Heph" })).getByRole(
			"switch",
			{ name: "Use this model" },
		);
		expect(unassignedSwitch.getAttribute("aria-checked")).toBe("false");
		expect(unassignedSwitch.getAttribute("aria-disabled")).toBe("true");
	});

	it("shows the binding it saves, so the payload cannot disagree with the controls", () => {
		const { onSave } = renderPage();

		const card = within(practiceDetectionCard());
		card.getByText(/GPT Test/);
		expect(card.getByRole("switch", { name: "Use this model" }).getAttribute("aria-checked")).toBe(
			"true",
		);

		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		expect(onSave).toHaveBeenCalledWith(
			"PRACTICE_REVIEW",
			expect.objectContaining({ instanceModelId: 20, enabled: true }),
			"UNCLASSIFIED",
		);
	});

	it("exposes the advanced settings as a disclosure", () => {
		renderPage();

		const card = within(practiceDetectionCard());
		const trigger = card.getByRole("button", { name: /Advanced/ });
		expect(trigger.getAttribute("aria-expanded")).toBe("false");
		expect(card.queryByLabelText("Timeout (seconds)")).toBeNull();

		fireEvent.click(trigger);

		expect(trigger.getAttribute("aria-expanded")).toBe("true");
		card.getByLabelText("Timeout (seconds)");
	});

	it("refuses to save a cleared timeout instead of sending a zero", () => {
		const { onSave } = renderPage();
		const card = within(practiceDetectionCard());

		fireEvent.click(card.getByRole("button", { name: /Advanced/ }));
		fireEvent.change(card.getByLabelText("Timeout (seconds)"), { target: { value: "" } });
		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		card.getByText("Enter a number of seconds.");
		expect(card.getByLabelText("Timeout (seconds)").getAttribute("aria-invalid")).toBe("true");
		expect(onSave).not.toHaveBeenCalled();
	});

	it("rejects a timeout below the floor and only saves once it is corrected", () => {
		const { onSave } = renderPage();
		const card = within(practiceDetectionCard());

		fireEvent.click(card.getByRole("button", { name: /Advanced/ }));
		const timeout = card.getByLabelText("Timeout (seconds)");
		fireEvent.change(timeout, { target: { value: "5" } });
		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		card.getByText("Enter a whole number of seconds, 30 or more.");
		expect(onSave).not.toHaveBeenCalled();

		fireEvent.change(timeout, { target: { value: "45" } });
		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		expect(onSave).toHaveBeenCalledWith(
			"PRACTICE_REVIEW",
			expect.objectContaining({ timeoutSeconds: 45 }),
			"UNCLASSIFIED",
		);
	});

	it("rejects a timeout above the ceiling, says why, and accepts the ceiling itself", () => {
		const { onSave } = renderPage();
		const card = within(practiceDetectionCard());

		fireEvent.click(card.getByRole("button", { name: /Advanced/ }));
		const timeout = card.getByLabelText("Timeout (seconds)");
		fireEvent.change(timeout, { target: { value: "14400" } });
		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		card.getByText("Runs stop after three hours, so enter 10800 seconds or less.");
		expect(timeout.getAttribute("aria-invalid")).toBe("true");
		expect(onSave).not.toHaveBeenCalled();

		fireEvent.change(timeout, { target: { value: "10800" } });
		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		expect(onSave).toHaveBeenCalledWith(
			"PRACTICE_REVIEW",
			expect.objectContaining({ timeoutSeconds: 10800 }),
			"UNCLASSIFIED",
		);
	});

	it("reopens the advanced disclosure when the field that blocked the save is inside it", () => {
		renderPage();
		const card = within(practiceDetectionCard());

		fireEvent.click(card.getByRole("button", { name: /Advanced/ }));
		fireEvent.change(card.getByLabelText("Max concurrent runs"), { target: { value: "0" } });
		fireEvent.click(card.getByRole("button", { name: /Advanced/ }));
		expect(card.queryByLabelText("Max concurrent runs")).toBeNull();

		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		card.getByText("Enter a whole number of runs, 1 or more.");
		expect(document.activeElement).toBe(card.getByLabelText("Max concurrent runs"));
	});

	it("keeps unrelated validation errors visible while another field is corrected", () => {
		renderPage();
		const card = within(practiceDetectionCard());

		fireEvent.click(card.getByRole("button", { name: /Advanced/ }));
		const timeout = card.getByLabelText("Timeout (seconds)");
		const concurrency = card.getByLabelText("Max concurrent runs");
		fireEvent.change(timeout, { target: { value: "" } });
		fireEvent.change(concurrency, { target: { value: "0" } });
		fireEvent.click(card.getByRole("button", { name: "Save assignment" }));

		fireEvent.change(timeout, { target: { value: "60" } });

		expect(card.queryByText("Enter a number of seconds.")).toBeNull();
		card.getByText("Enter a whole number of runs, 1 or more.");
	});

	it("offers Clear assignment only for a purpose that is actually bound", () => {
		const { onTurnOff } = renderPage();

		const clearAssignment = within(practiceDetectionCard()).getByRole("button", {
			name: "Clear assignment",
		});
		expect(screen.getAllByRole("button", { name: "Clear assignment" })).toHaveLength(1);

		fireEvent.click(clearAssignment);

		expect(onTurnOff).toHaveBeenCalledWith("PRACTICE_REVIEW", "UNCLASSIFIED");
	});
});
