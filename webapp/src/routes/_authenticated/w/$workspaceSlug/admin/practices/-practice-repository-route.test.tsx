import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

import type { ItemRef, PracticeRepository, ProblemDetail } from "@/api/types.gen";
import { definitionOf } from "@/components/admin/practice-editor/fixtures";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { Wire } from "@/lib/dates";
import { problemType } from "@/lib/problem-detail";
import { stringifySearch } from "@/lib/router-search";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

import { practiceSetupHandlers, practicesHandlers, wirePractice } from "./-practice-server";

beforeAll(async () => loadRouteCode("/w/acme/admin/practices"), ROUTE_RENDER_WAIT.timeout);

beforeEach(() => {
	server.use(
		...practiceSetupHandlers(),
		// The admin chrome reads the integration catalog for its menu.
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
	);
});

const SETTING_URL = "*/workspaces/:workspaceSlug/practice-repository";
const EDITED_IN_URL =
	"*/workspaces/:workspaceSlug/practice-repository/practices/:practiceSlug/edited-in";

const practiceRepository: PracticeRepository = {
	repositoryItemId: "7",
	nameWithOwner: "acme/practices",
	defaultBranch: "main",
	directory: ".hephaestus/practices",
};

const servedRepository = (repository: PracticeRepository | null) =>
	http.get(SETTING_URL, () =>
		repository === null ? new HttpResponse(null, { status: 204 }) : HttpResponse.json(repository),
	);

const repositoryItem = (id: string, label: string): Wire<ItemRef> => ({
	id,
	kind: "scm.repository",
	label,
	provider: "GITHUB",
});

/** The monitored repositories, which the choice reads; registered before the practice slug handler. */
const monitoredRepositories = () =>
	http.get("*/workspaces/:workspaceSlug/practices/items", () =>
		HttpResponse.json({
			content: [
				repositoryItem("7", "acme/practices"),
				repositoryItem("8", "acme/handbook"),
				{ ...repositoryItem("9", "acme/retired"), removedAt: "2026-10-01T09:00:00Z" },
			],
			page: 0,
			totalElements: 3,
			totalPages: 1,
		}),
	);

const stepUpRequired = () =>
	HttpResponse.json(
		{
			title: "Confirm access",
			status: 403,
			detail: "This action requires a recent sign-in. Sign in again to confirm access.",
			type: problemType("step-up-required"),
			maxAgeSeconds: 300,
		} satisfies ProblemDetail,
		{ status: 403 },
	);

const VALIDATION_FAILED = {
	title: "Validation failed",
	status: 400,
	detail: "Some fields need changes. Check your entries and try again.",
} satisfies ProblemDetail;

const definition = definitionOf({ name: "Explain the change" });
const editable = wirePractice({ slug: "explain-the-change", definition });
const inRepository = wirePractice({
	slug: "explain-the-change",
	definition,
	editedIn: "REPOSITORY",
	sourceCommit: "4f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39",
});
const PRACTICE_LEVEL = `/w/acme/admin/practices${stringifySearch(
	detailSearch({ kind: "practice", id: "explain-the-change" }),
)}`;

/** The card. Behind an open drawer level it is outside the accessibility tree, so `hidden` finds it. */
const cardOf = async (hidden = false) =>
	screen.findByRole("region", { name: "Practice repository", hidden }, ROUTE_RENDER_WAIT);

const openSetDialog = async () => {
	const card = await cardOf();
	fireEvent.click(
		await within(card).findByRole("button", { name: "Set practice repository" }, ROUTE_RENDER_WAIT),
	);
	return screen.findByRole("dialog", { name: "Set practice repository" });
};

const chooseRepository = async (dialog: HTMLElement, name: string) => {
	await userEvent.click(within(dialog).getByRole("combobox", { name: "Repository" }));
	await userEvent.click(await screen.findByRole("option", { name }, ROUTE_RENDER_WAIT));
};

const confirmMove = async () => {
	fireEvent.click(
		await screen.findByRole("button", { name: "Move to repository" }, ROUTE_RENDER_WAIT),
	);
	const dialog = await screen.findByRole("alertdialog", {
		name: "Move “Explain the change” to the practice repository?",
	});
	fireEvent.click(within(dialog).getByRole("button", { name: "Move to repository" }));
	return dialog;
};

/** Closes Confirm access, as a reader who does not sign in again now. */
const dismissConfirmAccess = async () => {
	const confirm = await screen.findByRole("dialog", { name: "Confirm access" }, ROUTE_RENDER_WAIT);
	fireEvent.click(within(confirm).getByRole("button", { name: "Close" }));
	await waitFor(
		async () => expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull(),
		ROUTE_RENDER_WAIT,
	);
};

describe("the practice repository card", () => {
	it("shows a workspace without one as not set, from the server's empty 204", async () => {
		server.use(servedRepository(null));

		renderRouteAt("/w/acme/admin/practices");
		const card = await cardOf();
		await within(card).findByText("Not set", {}, ROUTE_RENDER_WAIT);
		within(card).getByRole("button", { name: "Set practice repository" });
		// The page around it renders too: the read of "none" breaks nothing.
		screen.getByRole("heading", { name: "Practice setup" });
	});

	it("sets the repository, refreshes the card, and a reopened dialog starts fresh", async () => {
		const sent: unknown[] = [];
		const answer = deferred();
		const saved = { ...practiceRepository, directory: "practices" };
		server.use(
			servedRepository(null),
			monitoredRepositories(),
			http.put(SETTING_URL, async ({ request }) => {
				sent.push(await request.json());
				await answer.promise;
				server.use(servedRepository(saved));
				return HttpResponse.json(saved);
			}),
		);

		renderRouteAt("/w/acme/admin/practices");
		const card = await cardOf();
		const dialog = await openSetDialog();
		await userEvent.click(within(dialog).getByRole("combobox", { name: "Repository" }));
		// A repository the workspace removed is not one it monitors.
		await screen.findByRole("option", { name: "acme/handbook" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("option", { name: "acme/retired" })).toBeNull();
		await userEvent.click(screen.getByRole("option", { name: "acme/practices" }));
		await userEvent.type(within(dialog).getByRole("textbox", { name: "Directory" }), "practices");
		fireEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));

		await waitFor(
			async () => expect(sent).toStrictEqual([{ repositoryItemId: "7", directory: "practices" }]),
			ROUTE_RENDER_WAIT,
		);
		// The request runs with the dialog open and busy.
		await within(dialog).findByRole("button", { name: "Saving…" });
		answer.resolve();

		await within(card).findByText("acme/practices", {}, ROUTE_RENDER_WAIT);
		within(card).getByText("practices");
		const change = within(card).getByRole("button", { name: "Change" });
		// The write replaced Set practice repository, so focus lands on what took its place.
		await waitFor(async () => expect(document.activeElement).toBe(change), ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("dialog", { name: /practice repository/u })).toBeNull();

		// The request ended with the dialog: opening it again starts from the saved setting.
		fireEvent.click(change);
		const reopened = await screen.findByRole("dialog", { name: "Change practice repository" });
		expect(within(reopened).queryByRole("alert")).toBeNull();
		expect(
			within(reopened)
				.getByRole("button", { name: "Change practice repository" })
				.getAttribute("aria-disabled"),
		).toBe("true");
		expect(
			within(reopened).getByRole<HTMLInputElement>("textbox", { name: "Directory" }).value,
		).toBe("practices");
		expect(sent).toHaveLength(1);
	});

	it("keeps the server's reason for a refused repository in the dialog", async () => {
		server.use(
			servedRepository(null),
			monitoredRepositories(),
			http.put(SETTING_URL, () =>
				HttpResponse.json(
					{
						title: "Invalid request",
						status: 400,
						detail: "Choose a repository that this workspace monitors.",
					} satisfies ProblemDetail,
					{ status: 400 },
				),
			),
		);

		renderRouteAt("/w/acme/admin/practices");
		const dialog = await openSetDialog();
		await chooseRepository(dialog, "acme/practices");
		fireEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));

		const refusal = await within(dialog).findByRole("alert", {}, ROUTE_RENDER_WAIT);
		expect(refusal.textContent).toContain("We could not save the practice repository");
		expect(refusal.textContent).toContain("Choose a repository that this workspace monitors.");
	});

	it("marks the directory with the server's words when a 400 names it", async () => {
		server.use(
			servedRepository(null),
			monitoredRepositories(),
			http.put(SETTING_URL, () =>
				HttpResponse.json(
					{
						...VALIDATION_FAILED,
						errors: {
							directory: [
								"is a path relative to the repository root, without ., .. or .git segments",
							],
						},
					} satisfies ProblemDetail,
					{ status: 400 },
				),
			),
		);

		renderRouteAt("/w/acme/admin/practices");
		const dialog = await openSetDialog();
		await chooseRepository(dialog, "acme/practices");
		const directory = within(dialog).getByRole("textbox", { name: "Directory" });
		await userEvent.type(directory, "practices");
		fireEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));

		await waitFor(
			async () => expect(directory.getAttribute("aria-invalid")).toBe("true"),
			ROUTE_RENDER_WAIT,
		);
		const describedBy = (directory.getAttribute("aria-describedby") ?? "").split(" ");
		const described = describedBy.map((id) => document.getElementById(id)?.textContent).join(" ");
		expect(described).toContain(
			"The directory is a path relative to the repository root, without ., .. or .git segments.",
		);
		// The field says what to change, so the general words of the 400 stay out of the way.
		expect(within(dialog).queryByText(/Some fields need changes/u)).toBeNull();
		expect(within(dialog).getAllByRole("alert")).toHaveLength(1);
	});

	it("refuses a directory the server would refuse before it sends anything", async () => {
		let requests = 0;
		server.use(
			servedRepository(null),
			monitoredRepositories(),
			http.put(SETTING_URL, () => {
				requests += 1;
				return HttpResponse.json(practiceRepository);
			}),
		);

		renderRouteAt("/w/acme/admin/practices");
		const dialog = await openSetDialog();
		await chooseRepository(dialog, "acme/practices");
		const directory = within(dialog).getByRole("textbox", { name: "Directory" });
		await userEvent.type(directory, ".hephaestus/practices/");
		fireEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));

		await waitFor(
			async () => expect(directory.getAttribute("aria-invalid")).toBe("true"),
			ROUTE_RENDER_WAIT,
		);
		expect(within(dialog).getByRole("alert").textContent).toContain("no slash at either end");
		expect(requests).toBe(0);
	});

	it("asks for a recent sign-in after one request, then sets it on a fresh try", async () => {
		let requests = 0;
		server.use(
			servedRepository(null),
			monitoredRepositories(),
			http.put(
				SETTING_URL,
				() => {
					requests += 1;
					return stepUpRequired();
				},
				{ once: true },
			),
			http.put(SETTING_URL, () => {
				requests += 1;
				server.use(servedRepository(practiceRepository));
				return HttpResponse.json(practiceRepository);
			}),
		);

		renderRouteAt("/w/acme/admin/practices");
		const card = await cardOf();
		const dialog = await openSetDialog();
		await chooseRepository(dialog, "acme/practices");
		fireEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));

		await dismissConfirmAccess();
		expect(requests).toBe(1);
		expect(screen.queryByRole("dialog", { name: "Set practice repository" })).toBeNull();

		const retry = await openSetDialog();
		// The request ended with Confirm access: nothing of it is left in the new dialog.
		expect(within(retry).queryByRole("alert")).toBeNull();
		await chooseRepository(retry, "acme/practices");
		fireEvent.click(within(retry).getByRole("button", { name: "Set practice repository" }));

		await within(card).findByText("acme/practices", {}, ROUTE_RENDER_WAIT);
		expect(requests).toBe(2);
		await waitFor(
			async () => expect(screen.queryByRole("dialog", { name: /practice repository/u })).toBeNull(),
			ROUTE_RENDER_WAIT,
		);
	});

	it("unsets the repository after the admin confirms, and refreshes the card", async () => {
		let deletes = 0;
		server.use(
			servedRepository(practiceRepository),
			http.delete(SETTING_URL, () => {
				deletes += 1;
				// The read after the write fails, so only the write's answer can show the card unset.
				server.use(
					http.get(SETTING_URL, () =>
						HttpResponse.json({ title: "Bad Gateway", status: 502 } satisfies ProblemDetail, {
							status: 502,
						}),
					),
				);
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderRouteAt("/w/acme/admin/practices");
		const card = await cardOf();
		fireEvent.click(await within(card).findByRole("button", { name: "Unset" }, ROUTE_RENDER_WAIT));
		const dialog = await screen.findByRole("alertdialog", {
			name: "Unset the practice repository?",
		});
		expect(dialog.textContent).toContain(
			"Every practice edited in acme/practices becomes editable in Hephaestus again",
		);
		expect(deletes).toBe(0);
		fireEvent.click(within(dialog).getByRole("button", { name: "Unset" }));

		const set = await within(card).findByRole(
			"button",
			{ name: "Set practice repository" },
			ROUTE_RENDER_WAIT,
		);
		expect(deletes).toBe(1);
		await waitFor(async () => expect(document.activeElement).toBe(set), ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(within(card).queryByText("acme/practices")).toBeNull();
	});

	it("shows an Unset that another admin got to first as it now is", async () => {
		server.use(
			servedRepository(practiceRepository),
			http.delete(SETTING_URL, () => {
				server.use(servedRepository(null));
				return HttpResponse.json(
					{
						title: "Not Found",
						status: 404,
						detail: "This workspace has no practice repository.",
					} satisfies ProblemDetail,
					{ status: 404 },
				);
			}),
		);

		renderRouteAt("/w/acme/admin/practices");
		const card = await cardOf();
		fireEvent.click(await within(card).findByRole("button", { name: "Unset" }, ROUTE_RENDER_WAIT));
		const dialog = await screen.findByRole("alertdialog", {
			name: "Unset the practice repository?",
		});
		fireEvent.click(within(dialog).getByRole("button", { name: "Unset" }));

		const refusal = await within(dialog).findByRole("alert", {}, ROUTE_RENDER_WAIT);
		expect(refusal.textContent).toContain("This workspace has no practice repository.");
		// The card behind the dialog no longer claims the old repository.
		await within(card).findByText("Not set", {}, ROUTE_RENDER_WAIT);
		expect(within(card).queryByText("acme/practices")).toBeNull();
	});
});

describe("a change to the setting with a practice open", () => {
	beforeEach(() => {
		server.use(...practicesHandlers([inRepository]), servedRepository(practiceRepository));
	});

	it("makes an open practice edited in the repository editable after Unset", async () => {
		server.use(
			http.delete(SETTING_URL, () => {
				server.use(...practicesHandlers([editable]), servedRepository(null));
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderRouteAt(PRACTICE_LEVEL);
		await screen.findByText("Edited in acme/practices", {}, ROUTE_RENDER_WAIT);
		const card = await cardOf(true);
		fireEvent.click(within(card).getByRole("button", { name: "Unset", hidden: true }));
		const dialog = await screen.findByRole("alertdialog", {
			name: "Unset the practice repository?",
		});
		fireEvent.click(within(dialog).getByRole("button", { name: "Unset" }));

		await screen.findByRole("link", { name: "Edit practice" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Edited in acme/practices")).toBeNull();
	});

	it("makes an open practice edited in the old repository editable after Change", async () => {
		const handbook = {
			...practiceRepository,
			repositoryItemId: "8",
			nameWithOwner: "acme/handbook",
		};
		server.use(
			monitoredRepositories(),
			http.put(SETTING_URL, () => {
				server.use(...practicesHandlers([editable]), servedRepository(handbook));
				return HttpResponse.json(handbook);
			}),
		);

		renderRouteAt(PRACTICE_LEVEL);
		await screen.findByText("Edited in acme/practices", {}, ROUTE_RENDER_WAIT);
		const card = await cardOf(true);
		fireEvent.click(within(card).getByRole("button", { name: "Change", hidden: true }));
		const dialog = await screen.findByRole("dialog", { name: "Change practice repository" });
		await chooseRepository(dialog, "acme/handbook");
		fireEvent.click(within(dialog).getByRole("button", { name: "Change practice repository" }));

		await screen.findByRole("link", { name: "Edit practice" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Edited in acme/practices")).toBeNull();
		// The practice can move to the new repository from here.
		screen.getByText(/Move it to acme\/handbook/u);
	});
});

describe("moving a practice to the practice repository", () => {
	beforeEach(() => {
		server.use(...practicesHandlers([editable]), servedRepository(practiceRepository));
	});

	it("sends the move, then shows the practice read-only, and a later move back reopens nothing", async () => {
		const sent: { practiceSlug: unknown; body: unknown }[] = [];
		const answer = deferred();
		server.use(
			http.put(EDITED_IN_URL, async ({ params, request }) => {
				sent.push({ practiceSlug: params.practiceSlug, body: await request.json() });
				await answer.promise;
				server.use(...practicesHandlers([inRepository]));
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderRouteAt(PRACTICE_LEVEL);
		await screen.findByText("Edited in Hephaestus", {}, ROUTE_RENDER_WAIT);
		const dialog = await confirmMove();
		expect(dialog.textContent).toContain(".hephaestus/practices/explain-the-change/");

		await waitFor(
			async () =>
				expect(sent).toStrictEqual([
					{ practiceSlug: "explain-the-change", body: { editedIn: "REPOSITORY" } },
				]),
			ROUTE_RENDER_WAIT,
		);
		await within(dialog).findByRole("button", { name: "Moving it to the repository…" });
		expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveProperty("disabled", true);
		answer.resolve();

		// The practice reloads as edited in the repository, which closes the dialog onto the way back.
		await screen.findByText("Edited in acme/practices", {}, ROUTE_RENDER_WAIT);
		const back = screen.getByRole("button", { name: "Edit in Hephaestus" });
		await waitFor(async () => expect(document.activeElement).toBe(back), ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(screen.queryByRole("link", { name: "Edit practice" })).toBeNull();
		expect(screen.queryByRole("button", { name: "Move to repository" })).toBeNull();

		// Someone edits it in Hephaestus again; the reader closes the practice and opens it again.
		server.use(...practicesHandlers([editable]));
		fireEvent.keyDown(document.body, { key: "Escape" });
		await waitFor(async () => expect(screen.queryByRole("dialog")).toBeNull(), ROUTE_RENDER_WAIT);
		fireEvent.click(
			await screen.findByRole("link", { name: "Explain the change" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByText("Edited in Hephaestus", {}, ROUTE_RENDER_WAIT);
		// The request ended with the dialog: nothing reopens its confirmation.
		expect(screen.queryByRole("alertdialog")).toBeNull();
		screen.getByRole("button", { name: "Move to repository" });
		expect(sent).toHaveLength(1);
	});

	it("lists each problem of an invalid folder in the dialog", async () => {
		server.use(
			http.put(EDITED_IN_URL, () =>
				HttpResponse.json(
					{
						title: "Unprocessable Entity",
						status: 422,
						detail: "The folder is not a valid practice.",
						problems: [
							{
								file: "practice.md",
								line: 3,
								column: 7,
								code: "MISSING_KEY",
								message: "Name a kind of work.",
							},
							{ file: "notes.txt", code: "UNKNOWN_FILE", message: "Remove this file." },
						],
					} satisfies ProblemDetail,
					{ status: 422 },
				),
			),
		);

		renderRouteAt(PRACTICE_LEVEL);
		const dialog = await confirmMove();

		const refusal = await within(dialog).findByRole("alert", {}, ROUTE_RENDER_WAIT);
		expect(refusal.textContent).toContain("We could not move it to the repository");
		expect(refusal.textContent).toContain("The folder is not a valid practice.");
		const problems = within(
			within(refusal).getByRole("list", { name: "Problems in the folder" }),
		).getAllByRole("listitem");
		expect(problems.map((problem) => problem.textContent)).toStrictEqual([
			"practice.md, line 3, column 7: Name a kind of work.",
			"notes.txt: Remove this file.",
		]);
		// The practice stays editable here, and the dialog stays to try again.
		within(dialog).getByRole("button", { name: "Move to repository" });
		screen.getByText("Edited in Hephaestus");
	});

	it("shows the reason of a conflict in the dialog", async () => {
		server.use(
			http.put(EDITED_IN_URL, () =>
				HttpResponse.json(
					{
						title: "Conflict",
						status: 409,
						detail: "The default branch has no folder for this practice.",
					} satisfies ProblemDetail,
					{ status: 409 },
				),
			),
		);

		renderRouteAt(PRACTICE_LEVEL);
		const dialog = await confirmMove();

		const refusal = await within(dialog).findByRole("alert", {}, ROUTE_RENDER_WAIT);
		expect(refusal.textContent).toContain("The default branch has no folder for this practice.");
		expect(within(refusal).queryByRole("list")).toBeNull();
		screen.getByText("Edited in Hephaestus");
	});

	it("asks for a recent sign-in after one request, then moves it on a fresh try", async () => {
		let requests = 0;
		server.use(
			http.put(
				EDITED_IN_URL,
				() => {
					requests += 1;
					return stepUpRequired();
				},
				{ once: true },
			),
			http.put(EDITED_IN_URL, () => {
				requests += 1;
				server.use(...practicesHandlers([inRepository]));
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderRouteAt(PRACTICE_LEVEL);
		await confirmMove();
		await dismissConfirmAccess();
		expect(requests).toBe(1);
		expect(screen.queryByRole("alertdialog")).toBeNull();
		screen.getByText("Edited in Hephaestus");

		const retry = await confirmMove();
		expect(within(retry).queryByRole("alert")).toBeNull();
		await screen.findByText("Edited in acme/practices", {}, ROUTE_RENDER_WAIT);
		expect(requests).toBe(2);
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	it("offers the move unavailable, with a link to the setting, without a practice repository", async () => {
		server.use(servedRepository(null));

		renderRouteAt(PRACTICE_LEVEL);
		const move = await screen.findByRole(
			"button",
			{ name: "Move to repository" },
			ROUTE_RENDER_WAIT,
		);
		expect(move.getAttribute("aria-disabled")).toBe("true");
		fireEvent.click(move);
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(screen.getByRole("link", { name: "practice repository" }).getAttribute("href")).toBe(
			"/w/acme/admin/practices#practice-repository",
		);
	});
});
