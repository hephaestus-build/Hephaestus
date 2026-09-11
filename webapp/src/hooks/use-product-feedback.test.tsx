import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, renderHook, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { Toaster } from "sonner";
import { afterEach, describe, expect, it } from "vitest";

import { listProductSurveyInvitationsQueryKey } from "@/api/@tanstack/react-query.gen";
import type { SurveyInvitation } from "@/api/types.gen";
import { surveyInvitation } from "@/components/feedback/product-survey-fixtures";
import { server } from "@/mocks/server";
import { useProductSurveys, useSubmitProductFeedback } from "./use-product-feedback";

function setup() {
	const client = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return {
		client,
		wrapper: ({ children }: { children: ReactNode }) => (
			<QueryClientProvider client={client}>{children}</QueryClientProvider>
		),
	};
}

afterEach(cleanup);

describe("product feedback wire contract", () => {
	it("sends account feedback without a workspace or implicit page data", async () => {
		let received: unknown;
		server.use(
			http.post("*/product-feedback", async ({ request }) => {
				received = await request.json();
				return new HttpResponse(null, { status: 202 });
			}),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useSubmitProductFeedback(undefined), { wrapper });
		await act(async () => {
			expect(await result.current.submit({ kind: "BUG", message: "Cannot open reviews" })).toBe(
				true,
			);
		});
		expect(received).toStrictEqual({ kind: "BUG", message: "Cannot open reviews" });
	});

	it("keeps a failed submission actionable and does not automatically retry a POST", async () => {
		let requests = 0;
		server.use(
			http.post("*/workspaces/acme/product-feedback", () => {
				requests++;
				return HttpResponse.json({ status: 429 }, { status: 429 });
			}),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useSubmitProductFeedback("acme"), { wrapper });
		await act(async () => {
			expect(await result.current.submit({ kind: "FEEDBACK", message: "A draft" })).toBe(false);
		});
		await waitFor(() => expect(result.current.error).toContain("wait a minute"));
		expect(requests).toBe(1);
	});

	it("removes a submitted global survey from every cached workspace even when refresh fails", async () => {
		let received: unknown;
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json([surveyInvitation]),
			),
			http.post("*/workspaces/acme/product-feedback/surveys/:id/responses", async ({ request }) => {
				received = await request.json();
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { client, wrapper } = setup();
		const otherKey = listProductSurveyInvitationsQueryKey({ path: { workspaceSlug: "other" } });
		client.setQueryData(otherKey, [surveyInvitation]);
		const { result } = renderHook(() => useProductSurveys("acme"), { wrapper });
		await waitFor(() => expect(result.current.query.data).toHaveLength(1));
		server.use(http.get("*/workspaces/acme/product-feedback/surveys", () => HttpResponse.error()));
		await act(async () => {
			expect(
				await result.current.submit(surveyInvitation.id, [{ questionId: "useful", rating: 4 }]),
			).toBe(true);
		});
		expect(received).toStrictEqual({ answers: [{ questionId: "useful", rating: 4 }] });
		expect(client.getQueryData(otherKey)).toStrictEqual([]);
		await waitFor(() => expect(result.current.query.data).toStrictEqual([]));
	});

	it("drops a survey that was already settled elsewhere from the menu", async () => {
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json([surveyInvitation]),
			),
			http.post("*/workspaces/acme/product-feedback/surveys/:id/responses", () =>
				HttpResponse.json({ status: 409 }, { status: 409 }),
			),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useProductSurveys("acme"), { wrapper });
		await waitFor(() => expect(result.current.query.data).toHaveLength(1));
		server.use(http.get("*/workspaces/acme/product-feedback/surveys", () => HttpResponse.json([])));
		await act(async () => {
			expect(await result.current.submit(surveyInvitation.id, [])).toBe(false);
		});
		await waitFor(() => expect(result.current.error).toContain("already answered or declined"));
		await waitFor(() => expect(result.current.query.data).toStrictEqual([]));
	});

	it("replaces an earlier response conflict with the latest decline failure", async () => {
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json([surveyInvitation]),
			),
			http.post("*/workspaces/acme/product-feedback/surveys/:id/responses", () =>
				HttpResponse.json({ status: 409 }, { status: 409 }),
			),
			http.put("*/workspaces/acme/product-feedback/surveys/:id/decline", () =>
				HttpResponse.error(),
			),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useProductSurveys("acme"), { wrapper });
		await act(async () => {
			expect(
				await result.current.submit(surveyInvitation.id, [{ questionId: "useful", rating: 4 }]),
			).toBe(false);
		});
		await waitFor(() => expect(result.current.error).toContain("already answered or declined"));
		await act(async () => {
			expect(await result.current.decline(surveyInvitation.id)).toBe(false);
		});
		await waitFor(() => expect(result.current.error).toContain("Check your connection"));
	});

	it("shows the server's reason for a refusal instead of blaming the connection", async () => {
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json([surveyInvitation]),
			),
			http.post("*/workspaces/acme/product-feedback/surveys/:id/responses", () =>
				HttpResponse.json(
					{ status: 400, detail: "a required question was not answered" },
					{ status: 400 },
				),
			),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useProductSurveys("acme"), { wrapper });
		await act(async () => {
			expect(await result.current.submit(surveyInvitation.id, [])).toBe(false);
		});
		await waitFor(() =>
			expect(result.current.error).toBe(
				"Couldn't send (a required question was not answered). Your draft is still here.",
			),
		);
	});

	it("undoes a decline from the toast", async () => {
		let availableSurveys = [surveyInvitation];
		let undoneId: string | readonly string[] | undefined;
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () =>
				HttpResponse.json(availableSurveys),
			),
			http.put("*/workspaces/acme/product-feedback/surveys/:id/decline", () => {
				availableSurveys = [];
				return new HttpResponse(null, { status: 204 });
			}),
			http.delete("*/workspaces/acme/product-feedback/surveys/:id/decline", ({ params }) => {
				availableSurveys = [surveyInvitation];
				undoneId = params.id;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { wrapper } = setup();
		render(<Toaster />);
		const { result } = renderHook(() => useProductSurveys("acme"), { wrapper });
		await waitFor(() => expect(result.current.query.data).toHaveLength(1));
		await act(async () => {
			expect(await result.current.decline(surveyInvitation.id)).toBe(true);
		});
		await userEvent.click(await screen.findByRole("button", { name: "Undo" }));
		await waitFor(() => expect(result.current.query.data).toHaveLength(1));
		expect(undoneId).toBe(surveyInvitation.id);
	});

	it("marks an invitation as seen in every cached workspace without refetching", async () => {
		let acknowledged: string | readonly string[] | undefined;
		const unseen = { ...surveyInvitation, seen: false };
		server.use(
			http.get("*/workspaces/acme/product-feedback/surveys", () => HttpResponse.json([unseen])),
			http.put("*/workspaces/acme/product-feedback/surveys/:id/invitation", ({ params }) => {
				acknowledged = params.id;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { client, wrapper } = setup();
		const otherKey = listProductSurveyInvitationsQueryKey({ path: { workspaceSlug: "other" } });
		client.setQueryData(otherKey, [unseen]);
		const { result } = renderHook(() => useProductSurveys("acme"), { wrapper });
		await waitFor(() => expect(result.current.query.data?.[0]?.seen).toBe(false));
		act(() => result.current.acknowledge(surveyInvitation.id));
		await waitFor(() => expect(acknowledged).toBe(surveyInvitation.id));
		await waitFor(() => expect(result.current.query.data?.[0]?.seen).toBe(true));
		expect(client.getQueryData<SurveyInvitation[]>(otherKey)?.[0]?.seen).toBe(true);
	});

	it("sends one request when feedback is submitted twice at once", async () => {
		let requests = 0;
		server.use(
			http.post("*/product-feedback", () => {
				requests++;
				return new HttpResponse(null, { status: 202 });
			}),
		);
		const { wrapper } = setup();
		const { result } = renderHook(() => useSubmitProductFeedback(undefined), { wrapper });
		await act(async () => {
			const first = result.current.submit({ kind: "FEEDBACK", message: "One submission" });
			expect(await result.current.submit({ kind: "FEEDBACK", message: "Duplicate" })).toBe(false);
			expect(await first).toBe(true);
		});
		expect(requests).toBe(1);
	});

	it("does not send a survey without workspace context", async () => {
		const { wrapper } = setup();
		const { result } = renderHook(() => useProductSurveys(undefined), { wrapper });
		expect(await result.current.submit(surveyInvitation.id, [])).toBe(false);
		expect(await result.current.decline(surveyInvitation.id)).toBe(false);
	});
});
