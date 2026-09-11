import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { Survey, SurveySummary } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { isRecord } from "@/lib/is-record";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 30_000 });

const survey = {
	id: "11111111-1111-1111-1111-111111111111",
	title: "Help improve practice feedback",
	description: "Three quick questions.",
	questions: [
		{
			id: "improve",
			prompt: "What would make it more useful?",
			type: "TEXT",
			options: [],
			required: false,
			allowOther: false,
		},
	],
	purpose: "PRODUCT",
	startsAt: "2026-09-01T09:00:00.000Z",
	active: true,
	createdAt: "2026-09-01T09:00:00.000Z",
	createdBy: { id: 1, displayName: "Ada Lovelace" },
	participation: { invited: 42, responded: 17, declined: 4 },
} satisfies Wire<Survey>;

const summary = {
	participation: survey.participation,
	questions: [{ questionId: "improve", answered: 9, counts: [] }],
} satisfies Wire<SurveySummary>;

/** The JSON body of a request, as an object; `Request.json()` returns `any`. */
async function recordOf(request: Request): Promise<Record<string, unknown>> {
	const body: unknown = await request.json();
	return isRecord(body) ? body : {};
}

function mockSurveys(surveys: Wire<Survey>[]) {
	server.use(
		http.get("*/admin/product-feedback/surveys", () =>
			HttpResponse.json({
				content: surveys,
				page: { totalPages: 1, totalElements: surveys.length },
			}),
		),
		http.get("*/admin/product-feedback/surveys/:surveyId", () => HttpResponse.json(survey)),
		http.get("*/admin/product-feedback/surveys/:surveyId/summary", () =>
			HttpResponse.json(summary),
		),
		http.get("*/admin/product-feedback/surveys/:surveyId/responses", () =>
			HttpResponse.json({ content: [], page: { totalPages: 0, totalElements: 0 } }),
		),
		http.get("*/admin/workspaces", () =>
			HttpResponse.json([
				{
					id: 7,
					workspaceSlug: "acme",
					displayName: "Acme",
					accountLogin: "acme",
					createdAt: "2026-01-01T00:00:00Z",
					memberCount: 3,
					status: "ACTIVE",
				},
			]),
		),
	);
}

describe("instance surveys route", () => {
	it("lists the published surveys with their participation", async () => {
		mockSurveys([survey]);
		renderRouteAt("/admin/surveys");

		await screen.findByRole("link", { name: "Help improve practice feedback" }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("table", { name: "Surveys" }).textContent).toContain("17 of 42");
		expect(screen.getByRole("table", { name: "Surveys" }).textContent).toContain("40%");
	});

	it("pauses a survey by sending its current fields with the flag flipped", async () => {
		mockSurveys([survey]);
		const edits: unknown[] = [];
		server.use(
			http.put("*/admin/product-feedback/surveys/:surveyId", async ({ request }) => {
				edits.push(await request.json());
				return HttpResponse.json({ ...survey, active: false });
			}),
		);
		renderRouteAt("/admin/surveys");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Actions for Help improve practice feedback" },
				ROUTE_RENDER_WAIT,
			),
		);
		await user.click(await screen.findByRole("menuitem", { name: "Pause" }));

		await waitFor(() => expect(edits).toHaveLength(1));
		expect(edits[0]).toStrictEqual({
			title: survey.title,
			description: survey.description,
			startsAt: survey.startsAt,
			active: false,
		});
	});

	it("ends an open survey by sending its current fields with the end stamped now", async () => {
		mockSurveys([survey]);
		const edits: Record<string, unknown>[] = [];
		server.use(
			http.put("*/admin/product-feedback/surveys/:surveyId", async ({ request }) => {
				edits.push(await recordOf(request));
				return HttpResponse.json({ ...survey, endsAt: "2026-09-11T10:00:00.000Z" });
			}),
		);
		renderRouteAt("/admin/surveys");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Actions for Help improve practice feedback" },
				ROUTE_RENDER_WAIT,
			),
		);
		await user.click(await screen.findByRole("menuitem", { name: "End now" }));
		await user.click(await screen.findByRole("button", { name: "End survey" }));

		await waitFor(() => expect(edits).toHaveLength(1));
		const [edit] = edits;
		expect(typeof edit?.endsAt).toBe("string");
		expect(edit).toStrictEqual({
			title: survey.title,
			description: survey.description,
			startsAt: survey.startsAt,
			active: true,
			endsAt: edit?.endsAt,
		});
	});

	it("deletes a survey from its results and closes the level", async () => {
		mockSurveys([survey]);
		const deleted: string[] = [];
		server.use(
			http.delete("*/admin/product-feedback/surveys/:surveyId", ({ params }) => {
				deleted.push(String(params.surveyId));
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/admin/surveys");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole(
				"link",
				{ name: "Help improve practice feedback" },
				ROUTE_RENDER_WAIT,
			),
		);
		const drawer = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await user.click(
			await within(drawer).findByRole("button", {
				name: "Actions for Help improve practice feedback",
			}),
		);
		await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
		await user.click(await screen.findByRole("button", { name: "Delete survey" }));

		await waitFor(() => expect(deleted).toStrictEqual([survey.id]));
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	});

	it("says so when the export cannot be saved", async () => {
		mockSurveys([survey]);
		server.use(
			http.get("*/admin/product-feedback/surveys/:surveyId/responses/export", () =>
				HttpResponse.json({ status: 500 }, { status: 500 }),
			),
		);
		renderRouteAt("/admin/surveys");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole(
				"link",
				{ name: "Help improve practice feedback" },
				ROUTE_RENDER_WAIT,
			),
		);
		const drawer = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await user.click(await within(drawer).findByRole("button", { name: "Export CSV" }));

		await screen.findByText("Couldn't export the responses. Please try again.");
	});

	it("publishes a survey from the composer and opens its results", async () => {
		mockSurveys([]);
		const created: Record<string, unknown>[] = [];
		server.use(
			http.post("*/admin/product-feedback/surveys", async ({ request }) => {
				created.push(await recordOf(request));
				return HttpResponse.json(survey);
			}),
		);
		renderRouteAt("/admin/surveys");
		const user = userEvent.setup();

		await user.click(await screen.findByRole("link", { name: "Create survey" }, ROUTE_RENDER_WAIT));
		await screen.findByRole("heading", { name: "Create survey" }, ROUTE_RENDER_WAIT);

		await user.type(
			await screen.findByRole("textbox", { name: "Title" }),
			"Help improve practice feedback",
		);
		await user.type(
			screen.getByRole("textbox", { name: "Introduction" }),
			"Three quick questions.",
		);
		await user.type(
			screen.getByRole("textbox", { name: "Question" }),
			"What would make it more useful?",
		);
		// The instance runs no research programme, so there is no purpose to choose.
		expect(screen.queryByRole("radiogroup", { name: "Purpose" })).toBeNull();
		await user.click(screen.getByRole("button", { name: "Publish survey" }));

		await waitFor(() => expect(created).toHaveLength(1));
		const [body] = created;
		expect(body).toMatchObject({
			title: "Help improve practice feedback",
			description: "Three quick questions.",
			purpose: "PRODUCT",
			questions: [
				{
					prompt: "What would make it more useful?",
					type: "TEXT",
					options: [],
					required: false,
					allowOther: false,
				},
			],
		});
		// No audience and no end: neither key is sent, and the start is the publish instant.
		expect(typeof body?.startsAt).toBe("string");
		expect(body).not.toHaveProperty("workspaceId");
		expect(body).not.toHaveProperty("endsAt");

		// The results level replaces the composer, without asking about the draft it just published.
		await screen.findByRole(
			"heading",
			{ name: "Help improve practice feedback" },
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("button", { name: "Discard changes" })).toBeNull();
		expect(screen.queryByRole("textbox", { name: "Title" })).toBeNull();
	});

	it("offers a research purpose only where the instance names a study, and sends it", async () => {
		mockSurveys([]);
		const created: Record<string, unknown>[] = [];
		server.use(
			http.get("*/user/consent", () =>
				HttpResponse.json({
					completed: true,
					noticeVersion: "2026-09-11",
					participateInResearch: true,
					researchOrganization: "Technical University of Munich",
				}),
			),
			http.post("*/admin/product-feedback/surveys", async ({ request }) => {
				created.push(await recordOf(request));
				return HttpResponse.json({
					...survey,
					purpose: "RESEARCH",
					researchOrganization: "Technical University of Munich",
				});
			}),
		);
		renderRouteAt("/admin/surveys");
		const user = userEvent.setup();

		await user.click(await screen.findByRole("link", { name: "Create survey" }, ROUTE_RENDER_WAIT));
		await user.type(await screen.findByRole("textbox", { name: "Title" }), "Acting on feedback");
		await user.type(screen.getByRole("textbox", { name: "Introduction" }), "Part of the study.");
		await user.type(screen.getByRole("textbox", { name: "Question" }), "What did you do next?");
		await user.click(await screen.findByRole("radio", { name: "Research" }));
		await user.click(screen.getByRole("button", { name: "Publish survey" }));

		await waitFor(() => expect(created).toHaveLength(1));
		expect(created[0]).toMatchObject({ purpose: "RESEARCH" });
	});
});
