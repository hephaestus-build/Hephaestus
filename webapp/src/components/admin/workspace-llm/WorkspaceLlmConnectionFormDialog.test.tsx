import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { WorkspaceLlmConnection } from "@/api/types.gen";

import {
	WorkspaceLlmConnectionFormDialog,
	type WorkspaceLlmConnectionFormDialogProps,
} from "./WorkspaceLlmConnectionFormDialog";

const connection: WorkspaceLlmConnection = {
	id: 7,
	slug: "custom",
	displayName: "Custom endpoint",
	apiProtocol: "openai-responses",
	authMode: "BEARER",
	baseUrl: "https://llm.example.test/v1",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "ab12",
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

function renderDialog(onUpdate = vi.fn<WorkspaceLlmConnectionFormDialogProps["onUpdate"]>()) {
	render(
		<WorkspaceLlmConnectionFormDialog
			open
			onOpenChange={vi.fn()}
			editing={connection}
			isSubmitting={false}
			onCreate={vi.fn()}
			onUpdate={onUpdate}
		/>,
	);
	return onUpdate;
}

describe("WorkspaceLlmConnectionFormDialog", () => {
	it("starts a new connection inactive", () => {
		render(
			<WorkspaceLlmConnectionFormDialog
				open
				onOpenChange={vi.fn()}
				editing={null}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={vi.fn()}
			/>,
		);
		expect(screen.getByRole("switch", { name: "Active" }).getAttribute("aria-checked")).toBe(
			"false",
		);
	});

	it("keeps endpoint routing immutable after creation", () => {
		const onUpdate = renderDialog();
		expect(screen.getByLabelText<HTMLInputElement>("Base URL").disabled).toBe(true);
		expect(screen.queryByRole("combobox", { name: "Endpoint preset" })).toBeNull();
		expect(screen.queryByLabelText("Slug")).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		const update = onUpdate.mock.calls[0]?.[1];
		expect(update).toStrictEqual({
			displayName: "Custom endpoint",
			enabled: true,
		});
	});

	it("lets a workspace admin deactivate a connection and remove its stored key", () => {
		const onUpdate = renderDialog();
		fireEvent.click(screen.getByRole("switch", { name: "Active" }));
		screen.getByText("All workspace models will stop immediately");
		fireEvent.click(screen.getByRole("checkbox", { name: /remove stored api key/iu }));
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onUpdate).toHaveBeenCalledWith(
			connection.id,
			expect.objectContaining({ enabled: false, clearApiKey: true }),
		);
	});
});
