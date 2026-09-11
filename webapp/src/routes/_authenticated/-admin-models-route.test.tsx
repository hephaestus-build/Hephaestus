import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { LlmConnection, LlmModel } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

function mockPage(connections: LlmConnection[] = [], models: LlmModel[] = []) {
	server.use(
		http.get("*/admin/llm/connections", () => HttpResponse.json(connections)),
		http.get("*/admin/llm/models", () => HttpResponse.json(models)),
		http.get("*/admin/workspaces", () => HttpResponse.json([])),
		// Unmocked, the policy card's switch flips from controlled to uncontrolled mid-test.
		http.get("*/admin/llm/settings", () =>
			HttpResponse.json({ allowWorkspaceConnections: true, allowedEgressHosts: "" }),
		),
	);
}

async function renderModelsRoute() {
	renderRouteAt("/admin/models");
	return screen.findByRole("heading", { name: "AI models" }, ROUTE_RENDER_WAIT);
}

/** A connection with no models on it turns off without a confirm, so every fixture here has one. */
function model(id: number, connectionId: number, displayName: string) {
	return {
		id,
		connectionId,
		connectionDisplayName: `Connection ${connectionId}`,
		slug: `model-${id}`,
		displayName,
		upstreamModelId: `upstream-${id}`,
		enabled: true,
		visibility: "PUBLIC" as const,
		grantedWorkspaceIds: [],
		supportsReasoning: false,
		createdAt: new Date("2026-07-01T00:00:00Z"),
	};
}

function connection(id: number, displayName: string): LlmConnection {
	return {
		id,
		slug: `connection-${id}`,
		displayName,
		authMode: "BEARER",
		apiProtocol: "openai-responses",
		baseUrl: `https://provider-${id}.example.test/v1`,
		enabled: true,
		hasApiKey: true,
		apiKeyLast4: "1111",
		createdAt: new Date("2026-07-01T00:00:00Z"),
	};
}

describe("instance AI models route", () => {
	it("keeps each connection's toggle pending independently when two run at once", async () => {
		let releaseSlowToggle: (() => void) | undefined;
		const slowToggle = new Promise<void>((resolve) => {
			releaseSlowToggle = resolve;
		});
		let slowToggleCalls = 0;
		const connections = [connection(1, "Slow provider"), connection(2, "Fast provider")];
		const models = [model(11, 1, "Slow model"), model(12, 2, "Fast model")];
		mockPage(connections, models);
		server.use(
			http.patch("*/admin/llm/connections/1", async () => {
				slowToggleCalls += 1;
				await slowToggle;
				return HttpResponse.json({ ...connections[0], enabled: false });
			}),
			http.patch("*/admin/llm/connections/2", () =>
				HttpResponse.json({ ...connections[1], enabled: false }),
			),
		);

		await renderModelsRoute();

		const confirmTurnOff = async (name: string) => {
			fireEvent.click(await screen.findByRole("switch", { name }, ROUTE_RENDER_WAIT));
			const dialog = await screen.findByRole("alertdialog");
			fireEvent.click(within(dialog).getByRole("button", { name: "Turn off connection" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		};

		await confirmTurnOff("Slow provider");
		await waitFor(() => expect(slowToggleCalls).toBe(1));
		await confirmTurnOff("Fast provider");
		await waitFor(() =>
			expect(screen.getByRole("switch", { name: "Fast provider" }).getAttribute("aria-busy")).toBe(
				"false",
			),
		);

		expect(screen.getByRole("switch", { name: "Slow provider" }).getAttribute("aria-busy")).toBe(
			"true",
		);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Delete Slow provider" }).disabled,
		).toBe(true);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Delete Fast provider" }).disabled,
		).toBe(false);

		releaseSlowToggle?.();
		await waitFor(() => expect(slowToggleCalls).toBe(1));
	});

	it("lets the access dialog be dismissed while its save is still in flight", async () => {
		// There is no request timeout, so a dialog that refuses to close while `isPending` traps focus
		// with nothing left to release it.
		let releaseSlowSharing: (() => void) | undefined;
		const slowSharing = new Promise<void>((resolve) => {
			releaseSlowSharing = resolve;
		});
		let sharingCalls = 0;
		const connections = [connection(1, "Shared OpenAI")];
		const sharedModel = model(7, 1, "GPT Test");
		mockPage(connections, [sharedModel]);
		server.use(
			http.put("*/admin/llm/models/7/sharing", async () => {
				sharingCalls += 1;
				await slowSharing;
				return HttpResponse.json(sharedModel);
			}),
		);

		await renderModelsRoute();

		fireEvent.click(
			await screen.findByRole("button", { name: "Manage access for GPT Test" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("dialog");
		fireEvent.click(within(dialog).getByRole("button", { name: "Save access" }));
		await waitFor(() => expect(sharingCalls).toBe(1));

		fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		// The request was not cancelled, so the row stays disabled: no second save behind a dismissal.
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Manage access for GPT Test" })
				.disabled,
		).toBe(true);

		releaseSlowSharing?.();
		await waitFor(() => expect(sharingCalls).toBe(1));
	});

	it("asks for a fresh sign-in instead of reporting a refused connection as a failure", async () => {
		mockPage();
		server.use(
			http.post("*/admin/llm/connections", () =>
				HttpResponse.json(
					{ status: 403, code: "step_up_required", maxAgeSeconds: 300 },
					{ status: 403 },
				),
			),
			// The instance also offers GitHub, but this account has only ever signed in with GitLab.
			http.get("*/user/identities", () =>
				HttpResponse.json([{ id: 2, providerType: "GITLAB", username: "ada" }]),
			),
		);

		await renderModelsRoute();

		fireEvent.click(
			await screen.findByRole("button", { name: "Add connection" }, ROUTE_RENDER_WAIT),
		);
		const form = await screen.findByRole("dialog", { name: "Add connection" });
		fireEvent.change(within(form).getByLabelText("Display name"), {
			target: { value: "Production OpenAI" },
		});
		fireEvent.click(within(form).getByRole("button", { name: "Save inactive connection" }));

		const ask = await screen.findByRole("dialog", { name: "Confirm access" });
		expect(ask.textContent).toContain("sign-in from the last 5 minutes");
		expect(screen.getByRole("button", { name: "Continue with GitLab" })).not.toBeNull();
		// Signing in with GitHub here would resolve a different account and end this session.
		expect(screen.queryByRole("button", { name: "Continue with GitHub" })).toBeNull();
		// The ask replaces the form it came from rather than stacking on top of it.
		expect(screen.queryByRole("dialog", { name: "Add connection" })).toBeNull();
	});
});
