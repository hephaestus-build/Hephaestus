import { focusManager, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { Toaster } from "@/components/ui/sonner";
import environment from "@/environment";
import { server } from "@/mocks/server";
import { useUpdateNotice } from "./use-update-notice";

vi.mock("@/environment", () => ({ default: { version: "1.0.0" } }));

let queryClient: QueryClient;
let deployed: { version: string | number };
let requests = 0;
const reload = vi.fn();

function NoticeProbe() {
	useUpdateNotice();
	return null;
}

function renderNotice() {
	return render(
		<QueryClientProvider client={queryClient}>
			<NoticeProbe />
			<input aria-label="Draft" defaultValue="Keep this text" />
			<Toaster />
		</QueryClientProvider>,
	);
}

beforeEach(() => {
	queryClient = new QueryClient();
	environment.version = "1.0.0";
	deployed = { version: "1.0.1" };
	requests = 0;
	reload.mockClear();
	vi.stubGlobal("location", { origin: "http://localhost:4200", reload });
	server.use(
		http.get("http://localhost:4200/.well-known/hephaestus.json", () => {
			requests += 1;
			return HttpResponse.json(deployed);
		}),
	);
});

afterEach(() => {
	cleanup();
	queryClient.clear();
	focusManager.setFocused(undefined);
	vi.unstubAllGlobals();
	vi.useRealTimers();
});

it("offers an explicit reload after an update while leaving the current draft intact", async () => {
	renderNotice();
	await screen.findByText("Hephaestus was updated");
	expect(screen.getByRole<HTMLInputElement>("textbox", { name: "Draft" }).value).toBe(
		"Keep this text",
	);
	expect(reload).not.toHaveBeenCalled();
	fireEvent.click(screen.getByRole("button", { name: "Reload page" }));
	expect(reload).toHaveBeenCalledOnce();
});

it("checks again on focus and removes the notice if the deployment returns to this version", async () => {
	deployed = { version: "v1.0.0" };
	renderNotice();
	await waitFor(() => expect(requests).toBe(1));
	expect(screen.queryByText("Hephaestus was updated")).toBeNull();
	deployed = { version: "1.0.1" };
	await act(async () => {
		focusManager.setFocused(false);
		focusManager.setFocused(true);
	});
	await screen.findByText("Hephaestus was updated");
	deployed = { version: "1.0.0" };
	await act(async () => {
		focusManager.setFocused(false);
		focusManager.setFocused(true);
	});
	await waitFor(() => expect(screen.queryByText("Hephaestus was updated")).toBeNull());
});

it("does not announce an update for a malformed discovery file", async () => {
	deployed = { version: 101 };
	renderNotice();
	await waitFor(() => expect(requests).toBe(1));
	expect(screen.queryByText("Hephaestus was updated")).toBeNull();
});

it("does not poll in development", () => {
	environment.version = "DEV";
	renderNotice();
	expect(requests).toBe(0);
});

it("polls again while the tab stays open", async () => {
	vi.useFakeTimers();
	deployed = { version: "1.0.0" };
	renderNotice();
	await act(async () => {
		await vi.waitFor(() => expect(requests).toBe(1));
	});
	deployed = { version: "1.0.1" };
	await act(async () => {
		await vi.advanceTimersByTimeAsync(60_000);
	});
	await act(async () => {
		await vi.waitFor(() => expect(requests).toBe(2));
	});
	screen.getByText("Hephaestus was updated");
	expect(requests).toBe(2);
});
