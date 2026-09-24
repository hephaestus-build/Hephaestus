import { act, fireEvent, render, screen } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

import type { LlmConnection } from "@/api/types.gen";
import { validateLlmConnectionForm } from "@/lib/llm-form-validation";
import { hasText } from "@/lib/text";

import {
	AdminLlmConnectionFormDialog,
	type AdminLlmConnectionFormDialogProps,
} from "./AdminLlmConnectionFormDialog";

const connection: LlmConnection = {
	id: 1,
	slug: "custom",
	displayName: "Custom endpoint",
	apiProtocol: "openai-completions",
	authMode: "BEARER",
	baseUrl: "https://llm.example.test/v1",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "ab12",
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

function renderDialog(overrides: Partial<AdminLlmConnectionFormDialogProps> = {}) {
	const props: AdminLlmConnectionFormDialogProps = {
		open: true,
		onOpenChange: vi.fn(),
		editing: null,
		isSubmitting: false,
		onCreate: vi.fn(),
		onUpdate: vi.fn(),
		onProbe: vi.fn(),
		onProbeSaved: vi.fn(),
		isProbing: false,
		...overrides,
	};
	render(<AdminLlmConnectionFormDialog {...props} />);
	return props;
}

describe("AdminLlmConnectionFormDialog", () => {
	it("offers the three OpenAI-compatible create-time presets", () => {
		renderDialog();
		expect(screen.queryByRole("switch", { name: "Active" })).toBeNull();
		screen.getByText(/new connections start inactive/iu);
		expect(screen.queryByLabelText("Slug")).toBeNull();
		fireEvent.click(screen.getByRole("combobox", { name: "Endpoint preset" }));
		expect(screen.queryByRole("option", { name: "Anthropic" })).toBeNull();
		screen.getByRole("option", { name: "OpenAI" });
		screen.getByRole("option", { name: "Other OpenAI-compatible endpoint" });
		screen.getByRole("option", { name: "Azure OpenAI v1" });
	});

	it("keeps routing immutable and tests the saved connection with its stored credential", () => {
		const onUpdate = vi.fn<AdminLlmConnectionFormDialogProps["onUpdate"]>();
		const onProbe = vi.fn<AdminLlmConnectionFormDialogProps["onProbe"]>();
		const onProbeSaved = vi.fn<NonNullable<AdminLlmConnectionFormDialogProps["onProbeSaved"]>>();
		renderDialog({ editing: connection, onUpdate, onProbe, onProbeSaved });
		expect(screen.getByLabelText<HTMLInputElement>("Base URL").disabled).toBe(true);
		expect(screen.queryByRole("combobox", { name: "Endpoint preset" })).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Test saved connection" }));
		expect(onProbeSaved).toHaveBeenCalledWith(connection.id, expect.any(Object));
		expect(onProbe).not.toHaveBeenCalled();
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		const update = onUpdate.mock.calls[0]?.[1];
		expect(update).toStrictEqual({ displayName: "Custom endpoint" });
	});

	it("tests a replacement credential instead of reporting the old saved credential", () => {
		const onProbe = vi.fn<AdminLlmConnectionFormDialogProps["onProbe"]>();
		const onProbeSaved = vi.fn<NonNullable<AdminLlmConnectionFormDialogProps["onProbeSaved"]>>();
		renderDialog({ editing: connection, onProbe, onProbeSaved });

		screen.getByRole("button", { name: "Test saved connection" });
		fireEvent.change(screen.getByLabelText("API key"), { target: { value: "replacement-key" } });
		expect(screen.queryByRole("button", { name: "Test saved connection" })).toBeNull();

		fireEvent.click(screen.getByRole("button", { name: "Test changes" }));

		expect(onProbe).toHaveBeenCalledWith(
			expect.objectContaining({ apiKey: "replacement-key" }),
			expect.any(Object),
		);
		expect(onProbeSaved).not.toHaveBeenCalled();
	});

	it("refuses an endpoint that smuggles a credential into the URL", () => {
		const onCreate = vi.fn<AdminLlmConnectionFormDialogProps["onCreate"]>();
		renderDialog({ onCreate });
		fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Gateway" } });
		fireEvent.change(screen.getByLabelText("Base URL"), {
			target: { value: "https://gw.example.com/v1?api-key=SECRET" },
		});

		fireEvent.click(screen.getByRole("button", { name: "Save inactive connection" }));

		const rejection = validateLlmConnectionForm({
			displayName: "Gateway",
			baseUrl: "https://gw.example.com/v1?api-key=SECRET",
		}).baseUrl;
		assert(hasText(rejection), "A base URL carrying a secret must be rejected");
		screen.getByText(rejection);
		expect(onCreate).not.toHaveBeenCalled();
	});

	it("keeps a probe result while the connection is being named", () => {
		const onProbe = vi.fn<AdminLlmConnectionFormDialogProps["onProbe"]>();
		const onProbed = vi.fn<NonNullable<AdminLlmConnectionFormDialogProps["onProbed"]>>();
		renderDialog({ onProbe, onProbed });
		fireEvent.click(screen.getByRole("button", { name: "Test & fetch models" }));
		// The probe answers synchronously here, so `act` flushes the state it sets before the assertion.
		act(() => {
			onProbe.mock.calls[0]?.[1].onSuccess({ reachable: true, models: ["gpt-5"] });
		});
		screen.getByText("gpt-5");

		fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Production" } });

		screen.getByText("gpt-5");
		expect(onProbed).toHaveBeenLastCalledWith(["gpt-5"]);
	});

	it("ignores an in-flight probe after its connection inputs change", () => {
		const onProbe = vi.fn<AdminLlmConnectionFormDialogProps["onProbe"]>();
		const onProbed = vi.fn<NonNullable<AdminLlmConnectionFormDialogProps["onProbed"]>>();
		renderDialog({ onProbe, onProbed });
		fireEvent.click(screen.getByRole("button", { name: "Test & fetch models" }));
		const callbacks = onProbe.mock.calls[0]?.[1];
		assert(callbacks);

		fireEvent.change(screen.getByLabelText("Base URL"), {
			target: { value: "https://different.example.test/v1" },
		});
		callbacks.onSuccess({ reachable: true, models: ["wrong-endpoint-model"] });

		expect(screen.queryByText("wrong-endpoint-model")).toBeNull();
		expect(onProbed).not.toHaveBeenCalledWith(["wrong-endpoint-model"]);
	});
});
