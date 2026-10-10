import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
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

	it("creates a connection for the API the admin chooses", async () => {
		const onCreate = vi.fn<WorkspaceLlmConnectionFormDialogProps["onCreate"]>();
		render(
			<WorkspaceLlmConnectionFormDialog
				open
				onOpenChange={vi.fn()}
				editing={null}
				isSubmitting={false}
				onCreate={onCreate}
				onUpdate={vi.fn()}
			/>,
		);
		fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Reranker" } });
		await userEvent.click(screen.getByRole("combobox", { name: "API" }));
		await userEvent.click(
			await screen.findByRole("option", { name: "Rerank API (Cohere-compatible)" }),
		);
		screen.getByText("Choose a provider that reports token usage, or set No metered API cost.");
		// OpenAI serves no rerank API, so its address is gone and the admin names the endpoint.
		expect(screen.getByLabelText<HTMLInputElement>("Base URL").value).toBe("");
		fireEvent.change(screen.getByLabelText("Base URL"), {
			target: { value: "https://rerank.example.test/v1" },
		});
		await userEvent.click(screen.getByRole("button", { name: "Add connection" }));
		expect(onCreate).toHaveBeenCalledWith(
			expect.objectContaining({
				displayName: "Reranker",
				apiProtocol: "cohere-rerank",
				baseUrl: "https://rerank.example.test/v1",
			}),
		);
	});

	it("keeps endpoint routing immutable after creation", () => {
		const onUpdate = renderDialog();
		expect(screen.getByLabelText<HTMLInputElement>("Base URL").disabled).toBe(true);
		expect(screen.queryByRole("combobox", { name: "Endpoint preset" })).toBeNull();
		expect(screen.queryByRole("combobox", { name: "API" })).toBeNull();
		expect(screen.getByLabelText<HTMLInputElement>("API").value).toBe("Responses API");
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

	it("saves a declared connection platform", async () => {
		const onUpdate = renderDialog();
		await userEvent.click(screen.getByRole("combobox", { name: /Service receiving requests/u }));
		await userEvent.click(await screen.findByRole("option", { name: "Microsoft Azure" }));
		await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
		expect(onUpdate).toHaveBeenCalledWith(
			connection.id,
			expect.objectContaining({ connectionPlatform: "AZURE" }),
		);
	});
});
