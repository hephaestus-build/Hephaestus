import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, renderHook, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { deferred } from "@/test/async";

import { Toaster } from "@/components/ui/sonner";
import { server } from "@/mocks/server";
import { usePracticeFeedbackModeMutations } from "./use-practice-feedback-mode-mutations";

function renderMutations() {
	const client = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	render(<Toaster />);
	return renderHook(() => usePracticeFeedbackModeMutations("acme"), {
		wrapper: ({ children }: { children: ReactNode }) => (
			<QueryClientProvider client={client}>{children}</QueryClientProvider>
		),
	});
}

function refuseEveryPractice() {
	server.use(
		http.patch("*/workspaces/acme/practices/:practiceSlug/feedback-mode", () =>
			HttpResponse.json({ status: 500, detail: "The server failed." }, { status: 500 }),
		),
	);
}

describe("practice feedback mode messages", () => {
	it("reports a failed bulk change once, in its summary", async () => {
		refuseEveryPractice();
		const { result } = renderMutations();

		await act(async () => {
			await result.current.setManyPracticeFeedbackModes(["one", "two", "three"], "OFF");
		});

		await screen.findByText("We could not change 3 practices. Try again.");
		expect(screen.queryByText("We could not change the practice")).toBeNull();
		expect(document.querySelectorAll("[data-sonner-toast]")).toHaveLength(1);
	});

	it("still reports a failed single change itself", async () => {
		refuseEveryPractice();
		const { result } = renderMutations();

		act(() => {
			result.current.setPracticeFeedbackMode.mutate({
				path: { workspaceSlug: "acme", practiceSlug: "one" },
				body: { feedbackMode: "OFF" },
			});
		});

		await screen.findByText("We could not change the practice");
	});

	it("reports a single change that fails while a bulk change is still running", async () => {
		// The bulk write waits for the test; the separate single write fails at once.
		const bulkAnswer = deferred();
		server.use(
			http.patch("*/workspaces/acme/practices/bulk-one/feedback-mode", async () => {
				await bulkAnswer.promise;
				return HttpResponse.json({ status: 500, detail: "The server failed." }, { status: 500 });
			}),
			http.patch("*/workspaces/acme/practices/single/feedback-mode", () =>
				HttpResponse.json({ status: 500, detail: "The server failed." }, { status: 500 }),
			),
		);
		const { result } = renderMutations();

		let bulk: Promise<void> | undefined;
		act(() => {
			bulk = result.current.setManyPracticeFeedbackModes(["bulk-one"], "OFF");
		});
		act(() => {
			result.current.setPracticeFeedbackMode.mutate({
				path: { workspaceSlug: "acme", practiceSlug: "single" },
				body: { feedbackMode: "OFF" },
			});
		});
		await screen.findByText("We could not change the practice");

		bulkAnswer.resolve(undefined);
		await act(async () => {
			await bulk;
		});
		await screen.findByText("We could not change 1 practice. Try again.");
	});
});
