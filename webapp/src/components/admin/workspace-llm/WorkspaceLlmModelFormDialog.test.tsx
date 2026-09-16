import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { WorkspaceLlmModel } from "@/api/types.gen";

import { WorkspaceLlmModelFormDialog } from "./WorkspaceLlmModelFormDialog";

describe("WorkspaceLlmModelFormDialog", () => {
	it("keeps a new model inactive unless the workspace admin explicitly activates it", () => {
		const onCreate = vi.fn();
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

	it("keeps the upstream model identity immutable", () => {
		const onUpdate = vi.fn();
		const editing: WorkspaceLlmModel = {
			id: 1,
			slug: "gpt-5",
			displayName: "GPT-5",
			upstreamModelId: "gpt-5",
			connectionId: 1,
			connectionDisplayName: "OpenAI",
			enabled: false,
			supportsReasoning: true,
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
		const onUpdate = vi.fn();
		const editing: WorkspaceLlmModel = {
			id: 2,
			slug: "gpt-5-active",
			displayName: "GPT-5 active",
			upstreamModelId: "gpt-5",
			connectionId: 1,
			connectionDisplayName: "OpenAI",
			enabled: true,
			supportsReasoning: true,
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
});
