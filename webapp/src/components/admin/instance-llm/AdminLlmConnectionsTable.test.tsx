import { fireEvent, render, screen, within } from "@testing-library/react";
import { userEvent } from "storybook/test";
import { describe, expect, it, vi } from "vitest";

import type { LlmConnection } from "@/api/types.gen";
import { expectUnavailable } from "@/test/controls";

import {
	AdminLlmConnectionsTable,
	type AdminLlmConnectionsTableProps,
} from "./AdminLlmConnectionsTable";

const connection: LlmConnection = {
	id: 1,
	slug: "openai",
	displayName: "OpenAI production",
	baseUrl: "https://api.openai.com/v1",
	apiProtocol: "openai-responses",
	authMode: "BEARER",
	hasApiKey: true,
	enabled: true,
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

describe("AdminLlmConnectionsTable", () => {
	it("opens a connection's models from a keyboard-focusable button", async () => {
		const onSelect = vi.fn<AdminLlmConnectionsTableProps["onSelect"]>();
		render(
			<AdminLlmConnectionsTable
				connections={[connection]}
				modelCounts={{ 1: 2 }}
				isLoading={false}
				isError={false}
				mutatingIds={new Set<number>()}
				selectedId={null}
				onSelect={onSelect}
				onEdit={vi.fn<() => void>()}
				onToggleEnabled={vi.fn<() => void>()}
				onDelete={vi.fn<() => void>()}
			/>,
		);

		const manage = screen.getByRole("button", { name: "Manage models for OpenAI production" });
		manage.focus();
		expect(document.activeElement).toBe(manage);

		await userEvent.keyboard("{Enter}");
		expect(onSelect).toHaveBeenCalledWith(connection);
	});

	function renderTable(
		modelCounts: Record<number, number>,
		onToggleEnabled = vi.fn<AdminLlmConnectionsTableProps["onToggleEnabled"]>(),
	) {
		render(
			<AdminLlmConnectionsTable
				connections={[connection]}
				modelCounts={modelCounts}
				isLoading={false}
				isError={false}
				mutatingIds={new Set<number>()}
				selectedId={null}
				onSelect={vi.fn<() => void>()}
				onEdit={vi.fn<() => void>()}
				onToggleEnabled={onToggleEnabled}
				onDelete={vi.fn<() => void>()}
			/>,
		);
		return { onToggleEnabled };
	}

	it("confirms before turning off every model on a connection", async () => {
		const { onToggleEnabled } = renderTable({ 1: 2 });

		fireEvent.click(screen.getByRole("switch", { name: "OpenAI production" }));

		const confirm = screen.getByRole("alertdialog");
		expect(within(confirm).getByRole("heading").textContent).toBe("Turn off “OpenAI production”?");
		expect(confirm.textContent).toContain(
			"This immediately stops requests through all 2 models on this connection.",
		);
		expect(onToggleEnabled).not.toHaveBeenCalled();

		fireEvent.click(screen.getByRole("button", { name: "Turn off connection" }));
		expect(onToggleEnabled).toHaveBeenCalledWith(connection, false);
	});

	it("counts one model as one", () => {
		renderTable({ 1: 1 });

		fireEvent.click(screen.getByRole("switch", { name: "OpenAI production" }));

		expect(screen.getByRole("alertdialog").textContent).toContain(
			"This immediately stops requests through the model on this connection.",
		);
	});

	it("turns off a connection with no models without asking", () => {
		const { onToggleEnabled } = renderTable({});

		fireEvent.click(screen.getByRole("switch", { name: "OpenAI production" }));

		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(onToggleEnabled).toHaveBeenCalledWith(connection, false);
	});

	it("blocks turning off a connection until its affected models are known", async () => {
		render(
			<AdminLlmConnectionsTable
				connections={[connection]}
				modelCounts={{}}
				modelCountsAvailable={false}
				isLoading={false}
				isError={false}
				mutatingIds={new Set<number>()}
				selectedId={null}
				onSelect={vi.fn<() => void>()}
				onEdit={vi.fn<() => void>()}
				onToggleEnabled={vi.fn<() => void>()}
				onDelete={vi.fn<() => void>()}
			/>,
		);

		const toggle = screen.getByRole("switch", { name: "OpenAI production" });
		await expectUnavailable(toggle);
		screen.getByRole("cell", { name: "—" });

		fireEvent.click(toggle);
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(toggle.getAttribute("aria-checked")).toBe("true");
	});
});
