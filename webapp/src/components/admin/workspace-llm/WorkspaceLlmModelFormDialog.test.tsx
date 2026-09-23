import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { WorkspaceLlmModel } from "@/api/types.gen";

import {
	WorkspaceLlmModelFormDialog,
	type WorkspaceLlmModelFormDialogProps,
} from "./WorkspaceLlmModelFormDialog";

describe("WorkspaceLlmModelFormDialog", () => {
	it("keeps a new model inactive unless the workspace admin explicitly activates it", () => {
		const onCreate = vi.fn<WorkspaceLlmModelFormDialogProps["onCreate"]>();
		render(
			<WorkspaceLlmModelFormDialog
				open
				onOpenChange={vi.fn()}
				editing={null}
				isSubmitting={false}
				onCreate={onCreate}
				onUpdate={vi.fn()}
			/>,
		);
		expect(screen.getByRole("switch", { name: "Active" }).getAttribute("aria-checked")).toBe(
			"false",
		);
		expect(screen.queryByLabelText("Slug")).toBeNull();
		fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "GPU coder" } });
		fireEvent.change(screen.getByLabelText("Upstream model id"), {
			target: { value: "gpu-coder" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Add inactive model" }));
		expect(onCreate).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }));
	});

	it("sends no reasoning effort for a new model left at the provider default", () => {
		const onCreate = vi.fn<WorkspaceLlmModelFormDialogProps["onCreate"]>();
		render(
			<WorkspaceLlmModelFormDialog
				open
				onOpenChange={vi.fn()}
				editing={null}
				isSubmitting={false}
				onCreate={onCreate}
				onUpdate={vi.fn()}
			/>,
		);
		fireEvent.click(screen.getByRole("button", { name: "Limits and capabilities" }));
		expect(screen.getByRole("combobox", { name: "Reasoning effort" }).textContent).toContain(
			"Provider default",
		);
		fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "GPU coder" } });
		fireEvent.change(screen.getByLabelText("Upstream model id"), {
			target: { value: "gpu-coder" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Add inactive model" }));
		expect(onCreate.mock.calls[0]?.[0].reasoningEffort).toBeUndefined();
	});

	it("keeps a model's effort on save and clears it when set back to the provider default", async () => {
		const onUpdate = vi.fn<WorkspaceLlmModelFormDialogProps["onUpdate"]>();
		const editing: WorkspaceLlmModel = {
			dataHandlingTier: "UNDECLARED",
			id: 3,
			slug: "gpt-5-high",
			displayName: "GPT-5 high",
			upstreamModelId: "gpt-5",
			connectionId: 1,
			connectionDisplayName: "OpenAI",
			enabled: false,
			reasoningEffort: "HIGH",
			pricingMode: "UNPRICED",
			currency: "USD",
			createdAt: new Date("2026-07-01T00:00:00Z"),
		};
		render(
			<WorkspaceLlmModelFormDialog
				open
				onOpenChange={vi.fn()}
				editing={editing}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={onUpdate}
			/>,
		);
		fireEvent.click(screen.getByRole("button", { name: "Limits and capabilities" }));
		const select = screen.getByRole("combobox", { name: "Reasoning effort" });
		expect(select.textContent).toContain("High");
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onUpdate.mock.calls[0]?.[1]).toMatchObject({ reasoningEffort: "HIGH" });

		await userEvent.click(select);
		await userEvent.click(await screen.findByRole("option", { name: "Provider default" }));
		await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onUpdate.mock.calls[1]?.[1]).toMatchObject({ clearReasoningEffort: true });
		expect(onUpdate.mock.calls[1]?.[1]).not.toHaveProperty("reasoningEffort");
	});

	it("keeps the upstream model identity immutable", () => {
		const onUpdate = vi.fn<WorkspaceLlmModelFormDialogProps["onUpdate"]>();
		const editing: WorkspaceLlmModel = {
			dataHandlingTier: "UNDECLARED",
			id: 1,
			slug: "gpt-5",
			displayName: "GPT-5",
			upstreamModelId: "gpt-5",
			connectionId: 1,
			connectionDisplayName: "OpenAI",
			enabled: false,
			reasoningEffort: "MEDIUM",
			pricingMode: "UNPRICED",
			currency: "USD",
			createdAt: new Date("2026-07-01T00:00:00Z"),
		};
		render(
			<WorkspaceLlmModelFormDialog
				open
				onOpenChange={vi.fn()}
				editing={editing}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={onUpdate}
			/>,
		);
		expect(screen.getByLabelText<HTMLInputElement>("Upstream model id").disabled).toBe(true);
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onUpdate.mock.calls[0]?.[1]).not.toHaveProperty("upstreamModelId");
	});

	it("turns an active model off when its price becomes unknown", () => {
		const onUpdate = vi.fn<WorkspaceLlmModelFormDialogProps["onUpdate"]>();
		const editing: WorkspaceLlmModel = {
			dataHandlingTier: "UNDECLARED",
			id: 2,
			slug: "gpt-5-active",
			displayName: "GPT-5 active",
			upstreamModelId: "gpt-5",
			connectionId: 1,
			connectionDisplayName: "OpenAI",
			enabled: true,
			reasoningEffort: "MEDIUM",
			pricingMode: "PRICED",
			per1mInputUsd: 1,
			per1mOutputUsd: 2,
			currency: "USD",
			createdAt: new Date("2026-07-01T00:00:00Z"),
		};
		render(
			<WorkspaceLlmModelFormDialog
				open
				onOpenChange={vi.fn()}
				editing={editing}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={onUpdate}
			/>,
		);

		fireEvent.click(screen.getByRole("radio", { name: "Price not set" }));
		expect(screen.getByRole("switch", { name: "Active" }).getAttribute("aria-checked")).toBe(
			"false",
		);
		screen.getByText("Work on this model stops immediately");
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onUpdate.mock.calls[0]?.[1]).toStrictEqual(expect.objectContaining({ enabled: false }));
	});

	it("opens a declared model with its operator selected and the training guarantee already confirmed", () => {
		const onUpdate = vi.fn();
		const editing: WorkspaceLlmModel = {
			dataHandlingTier: "CLOUD",
			operatedBy: "PROVIDER",
			dataHandlingNote: "EU region",
			id: 3,
			slug: "gpt-5",
			displayName: "GPT-5",
			upstreamModelId: "gpt-5",
			connectionId: 1,
			connectionDisplayName: "OpenAI",
			enabled: false,
			pricingMode: "UNPRICED",
			currency: "USD",
			createdAt: new Date("2026-07-01T00:00:00Z"),
		};
		render(
			<WorkspaceLlmModelFormDialog
				open
				onOpenChange={vi.fn()}
				editing={editing}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={onUpdate}
			/>,
		);

		expect(screen.getByRole("radio", { name: "A provider" }).getAttribute("aria-checked")).toBe(
			"true",
		);
		expect(
			screen.getByRole("checkbox", { name: /rule out training/u }).getAttribute("aria-checked"),
		).toBe("true");
		screen.getByText("Cloud");
		expect(screen.queryByText(/stop serving/u)).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onUpdate.mock.calls[0]?.[1]).toStrictEqual(
			expect.objectContaining({ operatedBy: "PROVIDER", dataHandlingNote: "EU region" }),
		);

		// Leaving the stored tier drops the model out of the rows that hold it, and the form says so
		// before Save. Re-declaring it afterwards asks for the training guarantee again, and that
		// checkbox alone is the flagged fault.
		fireEvent.click(screen.getByRole("button", { name: "Leave undeclared" }));
		screen.getByText("Rows holding this model as Cloud stop serving");
		fireEvent.click(screen.getByRole("radio", { name: "Your organisation" }));
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		const error = screen.getByText(
			"Confirm the training guarantee, or leave data handling undeclared.",
		);
		const training = screen.getByRole("checkbox", { name: /rule out training/u });
		expect(training.getAttribute("aria-invalid")).toBe("true");
		expect(training.getAttribute("aria-describedby")).toBe(error.id);
		expect(
			screen.getByRole("radiogroup", { name: "Operated by" }).getAttribute("aria-invalid"),
		).not.toBe("true");
		expect(onUpdate).toHaveBeenCalledOnce();
	});
});
