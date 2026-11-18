import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, renderHook, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { Practice, ReviewedBy } from "@/api/types.gen";
import { definitionOf } from "@/components/admin/practice-editor/fixtures";
import { mockPracticeOf } from "@/components/admin/practices/fixtures";
import { Toaster } from "@/components/ui/sonner";
import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { server } from "@/mocks/server";
import { useBulkReviewerChange } from "./use-bulk-reviewer-change";

const LIMIT = { code: "diff-only", description: "It reads only the diff." };

function practice(slug: string, reviewedBy: Partial<ReviewedBy> = {}): Practice {
	return mockPracticeOf({
		id: slug,
		slug,
		definition: definitionOf({
			name: slug,
			reads: {
				contract: "2.0.0",
				sources: [{ source: "scm.pull-request.core", need: "REQUIRED" }],
			},
			reviewedBy: { by: "HEPHAESTUS", humanReason: null, limits: [LIMIT], ...reviewedBy },
		}),
	});
}

function people(reason: string): Partial<ReviewedBy> {
	return { by: "PEOPLE", humanReason: { code: "people", description: reason } };
}

/** Answers each save with the practice it received, and keeps every body it saw. */
function serve(refuse: Record<string, { status: number; detail: string }> = {}) {
	const saved = new Map<string, unknown>();
	server.use(
		http.get("*/workspaces/acme/practices/definition-options", () =>
			HttpResponse.json(mockPracticeDefinitionOptions),
		),
		http.get(
			"*/workspaces/acme/practices/:practiceSlug/guidance",
			() => new HttpResponse(null, { status: 404 }),
		),
		http.get("*/workspaces/acme/practices", () => HttpResponse.json([])),
		http.patch("*/workspaces/acme/practices/:practiceSlug", async ({ params, request }) => {
			const slug = String(params.practiceSlug);
			const refusal = refuse[slug];
			if (refusal) {
				return HttpResponse.json(refusal, { status: refusal.status });
			}
			saved.set(slug, await request.json());
			return HttpResponse.json(practice(slug));
		}),
	);
	return saved;
}

function renderChange() {
	const client = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	render(<Toaster />);
	return renderHook(() => useBulkReviewerChange("acme"), {
		wrapper: ({ children }: { children: ReactNode }) => (
			<QueryClientProvider client={client}>{children}</QueryClientProvider>
		),
	});
}

describe("useBulkReviewerChange", () => {
	it("saves a new version under the current source contract and keeps the limits", async () => {
		const saved = serve();
		const { result } = renderChange();

		await act(async () => {
			await result.current.setManyReviewers([practice("one"), practice("two")], { by: "NOBODY" });
		});

		expect(saved.get("one")).toMatchObject({
			expectedVersionId: "one01",
			definition: {
				reads: { contract: mockPracticeDefinitionOptions.sourceContract },
				reviewedBy: { by: "NOBODY", humanReason: null, limits: [LIMIT] },
			},
		});
		expect(saved.has("two")).toBe(true);
		await screen.findByText("Changed 2 practices.");
		expect(result.current.bulk).toBeNull();
	});

	it("keeps going past a refused practice and names it with the server's reason", async () => {
		const saved = serve({ one: { status: 409, detail: "A newer version exists." } });
		const { result } = renderChange();

		await act(async () => {
			await result.current.setManyReviewers([practice("one"), practice("two")], { by: "NOBODY" });
		});

		expect(saved.has("two")).toBe(true);
		await screen.findByText("Changed 1 of 2. 1 practice kept their reviewer.");
		await screen.findByText("one: A newer version exists. Reload the page and try again.");
	});

	it("leaves a practice that already has the reviewer and says so", async () => {
		const saved = serve();
		const { result } = renderChange();

		await act(async () => {
			await result.current.setManyReviewers([practice("one", { by: "NOBODY" }), practice("two")], {
				by: "NOBODY",
			});
		});

		expect([...saved.keys()]).toStrictEqual(["two"]);
		await screen.findByText("Changed 1 practice. 1 practice already had this reviewer.");
	});

	it("rewrites a People reason that differs and leaves one that matches", async () => {
		const saved = serve();
		const { result } = renderChange();

		await act(async () => {
			await result.current.setManyReviewers(
				[
					practice("same", people("Tone needs a person.")),
					practice("other", people("Something else.")),
				],
				{ by: "PEOPLE", reason: "  Tone needs a person. " },
			);
		});

		expect([...saved.keys()]).toStrictEqual(["other"]);
		expect(saved.get("other")).toMatchObject({
			definition: {
				reviewedBy: { by: "PEOPLE", humanReason: { description: "Tone needs a person." } },
			},
		});
	});

	it("reports a practice whose definition cannot be read without saving it", async () => {
		const saved = serve();
		const { result } = renderChange();
		const unreadable = practice("unreadable");

		await act(async () => {
			await result.current.setManyReviewers(
				[{ ...unreadable, currentVersion: { ...unreadable.currentVersion, definition: null } }],
				{ by: "NOBODY" },
			);
		});

		expect(saved.size).toBe(0);
		await screen.findByText("We could not change 1 practice.");
	});

	it("saves nothing when every selected practice already has the reviewer", async () => {
		const saved = serve();
		const { result } = renderChange();

		await act(async () => {
			await result.current.setManyReviewers([practice("one", { by: "NOBODY" })], { by: "NOBODY" });
		});

		expect(saved.size).toBe(0);
		await screen.findByText("Every selected practice already has this reviewer");
	});
});
