import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import type { PracticeReleaseProposal } from "@/api/types.gen";
import { definitionOf } from "@/components/admin/practice-editor/fixtures";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { Wire } from "@/lib/dates";
import { stringifySearch } from "@/lib/router-search";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

import {
	practiceSetupHandlers,
	practicesHandlers,
	wireGroup,
	wirePractice,
} from "./-practice-server";

vi.setConfig({ testTimeout: 20_000 });

const at = (kind: "practice" | "practice-edit", id: string) =>
	`/w/acme/admin/practices${stringifySearch(detailSearch({ kind, id }))}`;

beforeEach(() => {
	server.use(...practiceSetupHandlers());
});

describe("resetting a practice to the catalog after declining its update", () => {
	const ours = definitionOf({ name: "Explain the change", criteria: "Our criteria" });
	const offered = { ...ours, criteria: "New catalog criteria" };
	const declined = wirePractice({
		slug: "explain-the-change",
		definition: ours,
		versionId: "803",
		versionNumber: 3,
		catalogOrigin: {
			catalogSlug: "explain-the-change",
			state: "EDITED_HERE",
			localEdits: ["CRITERIA"],
			offeredDigest: "sha256:offered",
		},
	});
	const proposal: Wire<PracticeReleaseProposal> = {
		practiceSlug: "explain-the-change",
		currentVersionId: "803",
		base: offered,
		current: ours,
		offered,
		offeredDigest: "sha256:offered",
		etag: "reset-basis",
		fields: [{ field: "CRITERIA", changedLocally: true, offeredChanged: false, conflict: false }],
	};

	it("shows the concrete difference, then saves the catalog's version as a new version", async () => {
		const seenIfMatch = vi.fn<(tag: string | null) => void>();
		const reset = wirePractice({
			slug: "explain-the-change",
			definition: offered,
			versionId: "804",
			versionNumber: 4,
			catalogOrigin: { ...declined.catalogOrigin, state: "FROM_HEPHAESTUS", localEdits: [] },
		});
		server.use(
			...practicesHandlers([declined]),
			http.get("*/workspaces/:workspaceSlug/practices/releases/:slug", () =>
				HttpResponse.json(proposal),
			),
			http.delete(
				"*/workspaces/:workspaceSlug/practices/:practiceSlug/catalog-customization",
				({ request }) => {
					seenIfMatch(request.headers.get("If-Match"));
					server.use(...practicesHandlers([reset]));
					return HttpResponse.json(reset);
				},
			),
		);

		renderRouteAt(at("practice", "explain-the-change"));
		fireEvent.click(
			await screen.findByRole("button", { name: "Reset to catalog" }, ROUTE_RENDER_WAIT),
		);

		const dialog = await screen.findByRole("alertdialog", {
			name: "Reset “Explain the change” to the catalog?",
		});
		const criteria = await within(dialog).findByRole("rowheader", { name: "Review criteria" });
		expect(criteria.closest("tr")?.textContent).toBe(
			"Review criteriaOur criteriaNew catalog criteria",
		);
		within(dialog).getByText(/saved as a new version/u);
		expect(seenIfMatch).not.toHaveBeenCalled();

		fireEvent.click(within(dialog).getByRole("button", { name: "Reset to catalog" }));

		await waitFor(() => expect(seenIfMatch).toHaveBeenCalledWith('"reset-basis"'));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull(), ROUTE_RENDER_WAIT);
		const panel = await screen.findByRole("dialog", { name: /Explain the change/u });
		await within(panel).findByText("Version 4", {}, ROUTE_RENDER_WAIT);
		await within(panel).findByText("Same as the catalog");
		expect(screen.queryByRole("button", { name: "Reset to catalog" })).toBeNull();
	});

	it("keeps the practice and shows the new comparison when the offer changed meanwhile", async () => {
		server.use(
			...practicesHandlers([declined]),
			http.get(
				"*/workspaces/:workspaceSlug/practices/releases/:slug",
				() => HttpResponse.json(proposal),
				{ once: true },
			),
			http.get("*/workspaces/:workspaceSlug/practices/releases/:slug", () =>
				HttpResponse.json({
					...proposal,
					offered: { ...offered, criteria: "Newer catalog criteria" },
					etag: "newer-basis",
				}),
			),
			http.delete("*/workspaces/:workspaceSlug/practices/:practiceSlug/catalog-customization", () =>
				HttpResponse.json(
					{ title: "Practice release changed", status: 412, detail: "The offer changed." },
					{ status: 412 },
				),
			),
		);

		renderRouteAt(at("practice", "explain-the-change"));
		fireEvent.click(
			await screen.findByRole("button", { name: "Reset to catalog" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		fireEvent.click(await within(dialog).findByRole("button", { name: "Reset to catalog" }));

		const refused = await within(dialog).findByRole("alert", {}, ROUTE_RENDER_WAIT);
		expect(refused.textContent).toContain("Nothing was reset.");
		await within(dialog).findByText("Newer catalog criteria");
		screen.getByText("Version 3");
	});
});

describe("editing a workspace practice", () => {
	const practice = wirePractice({
		slug: "keep-the-change-reviewable",
		definition: definitionOf(),
		groupSlug: "review-ready-work",
		versionId: "701",
	});

	beforeEach(() => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-groups", () =>
				HttpResponse.json([
					wireGroup("review-ready-work", "Review-ready work"),
					wireGroup("testing", "Testing what changed", { position: 1 }),
				]),
			),
			...practicesHandlers([practice]),
		);
	});

	it("sends one complete definition and moves the practice in a separate call", async () => {
		const updated = vi.fn<(body: unknown) => void>();
		const moved = vi.fn<(body: unknown) => void>();
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/:practiceSlug", async ({ request }) => {
				updated(await request.json());
				return HttpResponse.json({
					...practice,
					currentVersion: { ...practice.currentVersion, id: "702", number: 2 },
				});
			}),
			http.put("*/workspaces/:workspaceSlug/practices/:practiceSlug/group", async ({ request }) => {
				moved(await request.json());
				return HttpResponse.json({ ...practice, groupSlug: "testing" });
			}),
		);

		renderRouteAt(at("practice-edit", practice.slug));
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT), {
			target: { value: "Keep each change reviewable" },
		});
		await userEvent.click(screen.getByRole("combobox", { name: "Practice group" }));
		await userEvent.click(await screen.findByRole("option", { name: "Testing what changed" }));
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await waitFor(
			() => expect(moved).toHaveBeenCalledWith({ groupSlug: "testing" }),
			ROUTE_RENDER_WAIT,
		);
		expect(updated).toHaveBeenCalledExactlyOnceWith({
			expectedVersionId: "701",
			definition: { ...currentDefinition(practice), name: "Keep each change reviewable" },
		});
	});

	it("submits a human reason without a code and displays the saved server definition", async () => {
		const savedDefinition = definitionOf({
			reviewedBy: {
				by: "PEOPLE",
				humanReason: { code: "SERVER_REASON", description: "Discussed off the work." },
				limits: [],
			},
			reads: { contract: currentDefinition(practice).reads.contract, sources: [] },
		});
		const saved = wirePractice({
			slug: practice.slug,
			definition: savedDefinition,
			groupSlug: practice.groupSlug,
			versionId: "702",
			versionNumber: 2,
		});
		let body: unknown;
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/:practiceSlug", async ({ request }) => {
				body = await request.json();
				server.use(...practicesHandlers([saved]));
				return HttpResponse.json(saved);
			}),
		);
		renderRouteAt(at("practice-edit", practice.slug));
		fireEvent.click(await screen.findByRole("radio", { name: /^People/u }, ROUTE_RENDER_WAIT));
		fireEvent.change(screen.getByRole("textbox", { name: /Why do people review it/u }), {
			target: { value: "Discussed off the work." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await waitFor(() => expect(body).toBeDefined(), ROUTE_RENDER_WAIT);
		expect(body).toStrictEqual({
			expectedVersionId: "701",
			definition: {
				...savedDefinition,
				reviewedBy: {
					by: "PEOPLE",
					humanReason: { description: "Discussed off the work." },
					limits: [],
				},
			},
		});
		await userEvent.click(
			await screen.findByRole("link", { name: savedDefinition.name }, ROUTE_RENDER_WAIT),
		);
		await screen.findAllByText("Discussed off the work.", {}, ROUTE_RENDER_WAIT);
	});

	it("refuses a stale save without a new version, keeps the draft, and loads the newest on request", async () => {
		const newer = {
			...practice,
			currentVersion: {
				...practice.currentVersion,
				id: "702",
				number: 2,
				definition: { ...currentDefinition(practice), name: "Their name" },
			},
		};
		const moved = vi.fn();
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/:practiceSlug", () => {
				server.use(...practicesHandlers([newer]));
				return HttpResponse.json(
					{ title: "Practice version changed", status: 409, detail: "A newer version exists." },
					{ status: 409 },
				);
			}),
			http.put("*/workspaces/:workspaceSlug/practices/:practiceSlug/group", () => {
				moved();
				return HttpResponse.json([]);
			}),
		);

		renderRouteAt(at("practice-edit", practice.slug));
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT), {
			target: { value: "My name" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		const conflict = await screen.findByRole("alert", {}, ROUTE_RENDER_WAIT);
		expect(conflict.textContent).toContain("Someone saved a newer version");
		screen.getByDisplayValue("My name");
		expect(moved).not.toHaveBeenCalled();

		fireEvent.click(
			await within(conflict).findByRole("button", { name: "Load version 2" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByDisplayValue("Their name");
		expect(screen.queryByRole("alert")).toBeNull();
	});
});
