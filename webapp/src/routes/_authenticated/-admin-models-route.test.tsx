import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { LlmConnection, LlmModel } from "@/api/types.gen";
import { problemType } from "@/lib/problem-detail";
import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
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

let queryClient: ReturnType<typeof renderRouteAt> | undefined;

afterEach(async () => {
	// Unmount observers before cancelling requests and release cache timers before jsdom closes.
	await act(async () => {
		cleanup();
		await queryClient?.cancelQueries();
		queryClient?.clear();
	});
	queryClient = undefined;
});

async function renderModelsRoute() {
	queryClient = renderRouteAt("/admin/models");
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
		dataHandlingTier: "UNDECLARED" as const,
		enabled: true,
		visibility: "PUBLIC" as const,
		grantedWorkspaceIds: [],
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
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
		baseUrl: `https://provider-${id}.example.test/v1`,
		enabled: true,
		hasApiKey: true,
		apiKeyLast4: "1111",
		createdAt: new Date("2026-07-01T00:00:00Z"),
	};
}

/** Every refusal that asks for a fresh sign-in says so without a dialog, whether or not the ask appears. */
const STEP_UP_NOTICE = "Changes were not saved. Confirm access and try again.";

async function confirmTurnOff(name: string) {
	fireEvent.click(await screen.findByRole("switch", { name }, ROUTE_RENDER_WAIT));
	const dialog = await screen.findByRole("alertdialog");
	fireEvent.click(within(dialog).getByRole("button", { name: "Turn off connection" }));
	await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
}

describe("instance AI models route", () => {
	it("draws the models table under its real headers while the first read of models is in flight", async () => {
		const modelsRead = deferred();
		let modelsRequested = false;
		mockPage([connection(1, "Shared OpenAI")]);
		server.use(
			http.get("*/admin/llm/models", async () => {
				modelsRequested = true;
				await modelsRead.promise;
				return HttpResponse.json([model(7, 1, "GPT Test")]);
			}),
		);

		// A failed assertion must still answer the held read, or cleanup waits on it.
		try {
			await renderModelsRoute();
			// The connection is on screen, so it is selected, and its models are still being read.
			await screen.findByRole("switch", { name: "Shared OpenAI" }, ROUTE_RENDER_WAIT);
			await waitFor(() => expect(modelsRequested).toBe(true));

			// The region keeps the shape it resolves to, rather than a fixed-height spinner standing in.
			const table = screen.getByRole("table", { name: "Models on Shared OpenAI" });
			expect(table.getAttribute("aria-busy")).toBe("true");
			for (const header of ["Model", "Data handling", "Price", "Workspace access", "Status"]) {
				within(table).getByRole("columnheader", { name: header });
			}
			expect(screen.queryByRole("status", { name: "Loading models" })).toBeNull();
			expect(within(table).queryByText("No models yet", { exact: false })).toBeNull();

			modelsRead.resolve();
			await screen.findByText("GPT Test", undefined, ROUTE_RENDER_WAIT);
			expect(
				screen.getByRole("table", { name: "Models on Shared OpenAI" }).getAttribute("aria-busy"),
			).toBeNull();
		} finally {
			modelsRead.resolve();
		}
	});

	it("keeps each connection's toggle pending independently when two run at once", async () => {
		const slowToggle = deferred();
		let slowToggleCalls = 0;
		const connections = [connection(1, "Slow provider"), connection(2, "Fast provider")];
		const models = [model(11, 1, "Slow model"), model(12, 2, "Fast model")];
		mockPage(connections, models);
		server.use(
			http.patch("*/admin/llm/connections/1", async () => {
				slowToggleCalls += 1;
				await slowToggle.promise;
				return HttpResponse.json({ ...connections[0], enabled: false });
			}),
			http.patch("*/admin/llm/connections/2", () =>
				HttpResponse.json({ ...connections[1], enabled: false }),
			),
		);

		await renderModelsRoute();

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
			screen.getByRole<HTMLButtonElement>("button", { name: "Edit Slow provider" }).disabled,
		).toBe(true);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Edit Fast provider" }).disabled,
		).toBe(false);

		slowToggle.resolve();
		await waitFor(() =>
			expect(screen.getByRole("switch", { name: "Slow provider" }).getAttribute("aria-busy")).toBe(
				"false",
			),
		);
	});

	it("lets the access dialog be dismissed while its save is still in flight", async () => {
		// There is no request timeout, so a dialog that refuses to close while `isPending` traps focus
		// with nothing left to release it.
		const slowSharing = deferred();
		let sharingCalls = 0;
		const connections = [connection(1, "Shared OpenAI")];
		const sharedModel = model(7, 1, "GPT Test");
		mockPage(connections, [sharedModel]);
		server.use(
			http.put("*/admin/llm/models/7/sharing", async () => {
				sharingCalls += 1;
				await slowSharing.promise;
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

		slowSharing.resolve();
		await waitFor(() =>
			expect(
				screen.getByRole<HTMLButtonElement>("button", { name: "Manage access for GPT Test" })
					.disabled,
			).toBe(false),
		);
	});

	it("asks for a fresh sign-in and says the refused connection was not saved", async () => {
		mockPage();
		server.use(
			http.post("*/admin/llm/connections", () =>
				HttpResponse.json(
					{ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 },
					{ status: 403 },
				),
			),
			// The instance also offers GitHub, but this account has only ever signed in with GitLab.
			http.get("*/user", () =>
				HttpResponse.json({
					...currentUser,
					linkedProviders: [{ type: "GITLAB", serverUrl: "https://gitlab.lrz.de" }],
				}),
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
		fireEvent.click(within(form).getByRole("button", { name: "Add connection" }));

		const ask = await screen.findByRole("dialog", { name: "Confirm access" });
		expect(ask.textContent).toContain("sign-in from the last 5 minutes");
		expect(screen.getByRole("button", { name: "Continue with GitLab" })).not.toBeNull();
		// Signing in with GitHub here would resolve a different account and end this session.
		expect(screen.queryByRole("button", { name: "Continue with GitHub" })).toBeNull();
		// The ask replaces the form it came from rather than stacking on top of it.
		expect(screen.queryByRole("dialog", { name: "Add connection" })).toBeNull();
		await screen.findByText(STEP_UP_NOTICE);
	});

	it.each([
		{
			start: "an update of another connection",
			openHeld: "Edit First provider",
			heldTitle: "Edit connection",
			heldName: "First provider",
			submit: "Save changes",
		},
		{
			start: "a new connection",
			openHeld: "Add connection",
			heldTitle: "Add connection",
			heldName: "Production OpenAI",
			submit: "Add connection",
		},
	] as const)(
		"keeps a connection's edit open and savable while $start finishes",
		async ({ openHeld, heldTitle, heldName, submit }) => {
			const slowWrite = deferred();
			const writes: string[] = [];
			const updates: { id: number; displayName?: string }[] = [];
			let connectionReads = 0;
			const connections = [connection(1, "First provider"), connection(2, "Second provider")];
			mockPage(connections, [model(11, 1, "First model"), model(12, 2, "Second model")]);
			server.use(
				http.get("*/admin/llm/connections", () => {
					connectionReads += 1;
					return HttpResponse.json(connections);
				}),
				http.patch("*/admin/llm/connections/1", async () => {
					writes.push("PATCH 1");
					await slowWrite.promise;
					return HttpResponse.json(connections[0]);
				}),
				http.post("*/admin/llm/connections", async () => {
					writes.push("POST");
					await slowWrite.promise;
					return HttpResponse.json(connection(3, "Production OpenAI"));
				}),
				http.patch<PathParams, { displayName?: string }>(
					"*/admin/llm/connections/2",
					async ({ request }) => {
						const body = await request.json();
						updates.push({ id: 2, displayName: body.displayName });
						return HttpResponse.json(connection(2, "Second provider renamed"));
					},
				),
			);

			// A failed assertion must still answer the held write, or cleanup waits on it.
			try {
				await renderModelsRoute();
				fireEvent.click(await screen.findByRole("button", { name: openHeld }, ROUTE_RENDER_WAIT));
				const held = within(await screen.findByRole("dialog", { name: heldTitle }));
				// An edit keeps the stored name; a new connection needs one before it can be added.
				fireEvent.change(held.getByLabelText("Display name"), { target: { value: heldName } });
				fireEvent.click(held.getByRole("button", { name: submit }));
				await waitFor(() => expect(writes).toHaveLength(1));
				// The write has no timeout, so its dialog may still close while it runs.
				fireEvent.click(held.getByRole("button", { name: "Cancel" }));
				await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

				fireEvent.click(screen.getByRole("button", { name: "Edit Second provider" }));
				const secondDialog = await screen.findByRole("dialog", { name: "Edit connection" });
				const name = within(secondDialog).getByLabelText<HTMLInputElement>("Display name");
				expect(name.value).toBe("Second provider");
				fireEvent.change(name, { target: { value: "Second provider renamed" } });
				const save = within(secondDialog).getByRole<HTMLButtonElement>("button", {
					name: "Save changes",
				});
				const savableWhileOtherSaves = !save.disabled;

				const readsBefore = connectionReads;
				await act(async () => {
					slowWrite.resolve();
				});
				// The held write's success reads the connections again; by then it has done all it does.
				await waitFor(() => expect(connectionReads).toBeGreaterThan(readsBefore));
				expect({
					savableWhileOtherSaves,
					stillOpen: screen.queryByRole("dialog", { name: "Edit connection" }) === secondDialog,
					draft: name.value,
				}).toStrictEqual({
					savableWhileOtherSaves: true,
					stillOpen: true,
					draft: "Second provider renamed",
				});

				fireEvent.click(save);
				await waitFor(() =>
					expect(updates).toStrictEqual([{ id: 2, displayName: "Second provider renamed" }]),
				);
				expect(writes).toHaveLength(1);
			} finally {
				slowWrite.resolve();
			}
		},
	);

	it("keeps a newer connection draft open when a dismissed save later requires fresh sign-in", async () => {
		const user = userEvent.setup();
		const slowSave = deferred();
		const writes: string[] = [];
		const updates: { id: number; displayName?: string }[] = [];
		const connections = [connection(1, "First provider"), connection(2, "Second provider")];
		mockPage(connections, [model(11, 1, "First model"), model(12, 2, "Second model")]);
		server.use(
			http.patch("*/admin/llm/connections/1", async () => {
				writes.push("PATCH 1");
				await slowSave.promise;
				return HttpResponse.json(
					{ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 },
					{ status: 403 },
				);
			}),
			http.patch<PathParams, { displayName?: string }>(
				"*/admin/llm/connections/2",
				async ({ request }) => {
					const body = await request.json();
					updates.push({ id: 2, displayName: body.displayName });
					return HttpResponse.json(connection(2, "Second provider renamed"));
				},
			),
		);

		// A failed assertion must still answer the held save, or cleanup waits on it.
		try {
			await renderModelsRoute();
			const editFirst = await screen.findByRole<HTMLButtonElement>(
				"button",
				{ name: "Edit First provider" },
				ROUTE_RENDER_WAIT,
			);
			await user.click(editFirst);
			const first = within(await screen.findByRole("dialog", { name: "Edit connection" }));
			await user.click(first.getByRole("button", { name: "Save changes" }));
			await waitFor(() => expect(writes).toStrictEqual(["PATCH 1"]));
			// The save has no timeout, so its form may close while it runs; its row stays fenced.
			expect(editFirst.disabled).toBe(true);
			await user.click(first.getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

			await user.click(screen.getByRole("button", { name: "Edit Second provider" }));
			const secondDialog = await screen.findByRole("dialog", { name: "Edit connection" });
			const name = within(secondDialog).getByLabelText<HTMLInputElement>("Display name");
			await user.clear(name);
			await user.type(name, "Second provider renamed");
			const save = within(secondDialog).getByRole<HTMLButtonElement>("button", {
				name: "Save changes",
			});

			// The dismissed save now asks for a fresh sign-in.
			await act(async () => {
				slowSave.resolve();
			});
			// Its row unlocks only once the refusal has settled and every handler of it has run.
			await waitFor(() => expect(editFirst.disabled).toBe(false));
			expect({
				signInAsked: screen.queryByRole("dialog", { name: "Confirm access" }) !== null,
				dialogs: screen.queryAllByRole("dialog").length,
				alertDialogs: screen.queryAllByRole("alertdialog").length,
				stillOpen: screen.queryByRole("dialog", { name: "Edit connection" }) === secondDialog,
				focused: document.activeElement === name,
				draft: name.value,
				savable: !save.disabled,
			}).toStrictEqual({
				signInAsked: false,
				dialogs: 1,
				alertDialogs: 0,
				stillOpen: true,
				focused: true,
				draft: "Second provider renamed",
				savable: true,
			});
			await screen.findByText(STEP_UP_NOTICE);

			await user.click(save);
			await waitFor(() =>
				expect(updates).toStrictEqual([{ id: 2, displayName: "Second provider renamed" }]),
			);
			expect(writes).toStrictEqual(["PATCH 1"]);
		} finally {
			slowSave.resolve();
		}
	});

	it("keeps a newer access draft open and savable after a dismissed model access save", async () => {
		const user = userEvent.setup();
		const slowSharing = deferred();
		const sharingSaves: { id: number; body: unknown }[] = [];
		const first = model(7, 1, "GPT Test");
		const second = model(8, 1, "GPT Other");
		mockPage([connection(1, "Shared OpenAI")], [first, second]);
		server.use(
			http.put("*/admin/llm/models/7/sharing", async ({ request }) => {
				sharingSaves.push({ id: 7, body: await request.json() });
				await slowSharing.promise;
				return HttpResponse.json(first);
			}),
			http.put("*/admin/llm/models/8/sharing", async ({ request }) => {
				sharingSaves.push({ id: 8, body: await request.json() });
				return HttpResponse.json({ ...second, visibility: "GRANTED" });
			}),
		);

		// A failed assertion must still answer the held save, or cleanup waits on it.
		try {
			await renderModelsRoute();
			const manageFirst = await screen.findByRole<HTMLButtonElement>(
				"button",
				{ name: "Manage access for GPT Test" },
				ROUTE_RENDER_WAIT,
			);
			await user.click(manageFirst);
			const firstDialog = await screen.findByRole("dialog", {
				name: "Manage access to GPT Test",
			});
			await user.click(within(firstDialog).getByRole("button", { name: "Save access" }));
			await waitFor(() => expect(sharingSaves).toHaveLength(1));
			await user.click(within(firstDialog).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

			await user.click(screen.getByRole("button", { name: "Manage access for GPT Other" }));
			const secondDialog = await screen.findByRole("dialog", {
				name: "Manage access to GPT Other",
			});
			const selected = within(secondDialog).getByRole("radio", {
				name: /^Selected workspaces/u,
			});
			await user.click(selected);
			// Found by either label, so a save wrongly shown as running still yields an observation.
			const save = within(secondDialog).getByRole<HTMLButtonElement>("button", {
				name: /^(?:Save access|Saving…)$/u,
			});
			const whileFirstSaves = {
				firstRowFenced: manageFirst.disabled,
				savable: !save.disabled,
				label: save.textContent,
			};

			await act(async () => {
				slowSharing.resolve();
			});
			// The first row unlocks only once its save has settled and every handler of it has run.
			await waitFor(() => expect(manageFirst.disabled).toBe(false));
			expect({
				whileFirstSaves,
				stillOpen:
					screen.queryByRole("dialog", { name: "Manage access to GPT Other" }) === secondDialog,
				draft: selected.getAttribute("aria-checked"),
				savableAfter: !save.disabled,
			}).toStrictEqual({
				whileFirstSaves: { firstRowFenced: true, savable: true, label: "Save access" },
				stillOpen: true,
				draft: "true",
				savableAfter: true,
			});

			await user.click(save);
			await waitFor(() => expect(sharingSaves).toHaveLength(2));
			expect(sharingSaves).toStrictEqual([
				{ id: 7, body: { visibility: "PUBLIC" } },
				{ id: 8, body: { visibility: "GRANTED", workspaceIds: [] } },
			]);
		} finally {
			slowSharing.resolve();
		}
	});

	it("keeps a model draft open when an earlier connection row action requires fresh sign-in", async () => {
		const user = userEvent.setup();
		const slowToggle = deferred();
		const toggles: { enabled?: boolean }[] = [];
		mockPage([connection(1, "Shared OpenAI")], [model(7, 1, "GPT Test")]);
		server.use(
			http.patch<PathParams, { enabled?: boolean }>(
				"*/admin/llm/connections/1",
				async ({ request }) => {
					const body = await request.json();
					toggles.push(body);
					await slowToggle.promise;
					return HttpResponse.json(
						{ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 },
						{ status: 403 },
					);
				},
			),
		);

		// A failed assertion must still answer the held toggle, or cleanup waits on it.
		try {
			await renderModelsRoute();
			await confirmTurnOff("Shared OpenAI");
			await waitFor(() => expect(toggles).toStrictEqual([{ enabled: false }]));
			const toggle = screen.getByRole("switch", { name: "Shared OpenAI" });
			expect(toggle.getAttribute("aria-busy")).toBe("true");

			await user.click(screen.getByRole("button", { name: "Edit GPT Test" }));
			const modelDialog = await screen.findByRole("dialog", { name: "Edit model" });
			const name = within(modelDialog).getByLabelText<HTMLInputElement>("Display name");
			await user.clear(name);
			await user.type(name, "GPT Test renamed");
			const save = within(modelDialog).getByRole<HTMLButtonElement>("button", {
				name: "Save changes",
			});

			// The earlier toggle now asks for a fresh sign-in.
			await act(async () => {
				slowToggle.resolve();
			});
			// The switch stops being busy only once the refusal has settled and every handler of it has run.
			await waitFor(() => expect(toggle.getAttribute("aria-busy")).toBe("false"));
			expect({
				signInAsked: screen.queryByRole("dialog", { name: "Confirm access" }) !== null,
				dialogs: screen.queryAllByRole("dialog").length,
				alertDialogs: screen.queryAllByRole("alertdialog").length,
				stillOpen: screen.queryByRole("dialog", { name: "Edit model" }) === modelDialog,
				focused: document.activeElement === name,
				draft: name.value,
				savable: !save.disabled,
			}).toStrictEqual({
				signInAsked: false,
				dialogs: 1,
				alertDialogs: 0,
				stillOpen: true,
				focused: true,
				draft: "GPT Test renamed",
				savable: true,
			});
			await screen.findByText(STEP_UP_NOTICE);
			expect(toggles).toStrictEqual([{ enabled: false }]);
		} finally {
			slowToggle.resolve();
		}
	});

	it("does not revive a connection challenge after a newer model dialog was opened and closed", async () => {
		const user = userEvent.setup();
		const slowToggle = deferred();
		const toggles: { enabled?: boolean }[] = [];
		mockPage([connection(1, "Shared OpenAI")], [model(7, 1, "GPT Test")]);
		server.use(
			http.patch<PathParams, { enabled?: boolean }>(
				"*/admin/llm/connections/1",
				async ({ request }) => {
					const body = await request.json();
					toggles.push(body);
					await slowToggle.promise;
					return HttpResponse.json(
						{ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 },
						{ status: 403 },
					);
				},
			),
		);

		// A failed assertion must still answer the held toggle, or cleanup waits on it.
		try {
			await renderModelsRoute();
			await confirmTurnOff("Shared OpenAI");
			await waitFor(() => expect(toggles).toStrictEqual([{ enabled: false }]));
			const toggle = screen.getByRole("switch", { name: "Shared OpenAI" });
			expect(toggle.getAttribute("aria-busy")).toBe("true");

			// A newer dialog opens and is dismissed while the toggle is still out.
			await user.click(screen.getByRole("button", { name: "Edit GPT Test" }));
			const modelDialog = await screen.findByRole("dialog", { name: "Edit model" });
			await user.click(within(modelDialog).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

			// The earlier toggle now asks for a fresh sign-in.
			await act(async () => {
				slowToggle.resolve();
			});
			// The switch stops being busy only once the refusal has settled and every handler of it has run.
			await waitFor(() => expect(toggle.getAttribute("aria-busy")).toBe("false"));
			expect({
				signInAsked: screen.queryByRole("dialog", { name: "Confirm access" }) !== null,
				dialogs: screen.queryAllByRole("dialog").length,
				alertDialogs: screen.queryAllByRole("alertdialog").length,
				toggles,
			}).toStrictEqual({
				signInAsked: false,
				dialogs: 0,
				alertDialogs: 0,
				toggles: [{ enabled: false }],
			});
			await screen.findByText(STEP_UP_NOTICE);
		} finally {
			slowToggle.resolve();
		}
	});
});
