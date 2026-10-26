import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { useState } from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { clearUserView, getUserViewSession } from "@/runtime/user-view/session";
import { deferred } from "@/test/async";
import { captureNavigation, restoreNavigation } from "@/test/navigation";
import { testQueryClient } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

import { AuthProvider, useAuth } from "./AuthContext";

beforeEach(() => storeUserView());

afterEach(() => {
	clearUserView();
	restoreNavigation();
});

function SignOut() {
	const { logout, username } = useAuth();
	const [settled, setSettled] = useState(false);
	const signOut = async () => {
		await logout();
		setSettled(true);
	};
	return (
		<>
			<p>Acting as {username}</p>
			{settled && <p>Sign-out settled</p>}
			<button
				type="button"
				onClick={() => {
					void signOut();
				}}
			>
				Sign out
			</button>
		</>
	);
}

function renderSignOut() {
	render(
		<QueryClientProvider client={testQueryClient()}>
			<AuthProvider>
				<SignOut />
			</AuthProvider>
		</QueryClientProvider>,
	);
}

it("keeps the view and the viewed user when sign-out fails", async () => {
	server.use(http.post("*/auth/logout", () => HttpResponse.error()));
	renderSignOut();
	await screen.findByText("Acting as alex");

	await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
	await screen.findByText("Sign-out settled");

	expect(getUserViewSession()?.accountId).toBe("11");
	expect(screen.queryByText("Acting as alex")).not.toBeNull();
});

it("ends the view when sign-out succeeds", async () => {
	server.use(http.post("*/auth/logout", () => new HttpResponse(null, { status: 204 })));
	renderSignOut();
	await screen.findByText("Acting as alex");
	const assigned = captureNavigation();

	await userEvent.click(screen.getByRole("button", { name: "Sign out" }));
	await screen.findByText("Sign-out settled");

	expect(getUserViewSession()).toBeUndefined();
	expect(assigned).toStrictEqual(["/"]);
});

function Session() {
	const { session } = useAuth();
	return <p>Session: {session}</p>;
}

function renderSession() {
	render(
		<QueryClientProvider client={testQueryClient()}>
			<AuthProvider>
				<Session />
			</AuthProvider>
		</QueryClientProvider>,
	);
}

it("reads an unreachable server as unavailable, not signed out, while it asks again", async () => {
	vi.useFakeTimers({ shouldAdvanceTime: true });
	try {
		const answer = deferred<Response>();
		server.use(http.get("*/user", async () => answer.promise));
		server.use(http.get("*/user", () => new HttpResponse(null, { status: 503 }), { once: true }));
		renderSession();
		await screen.findByText("Session: unavailable");

		await act(async () => vi.advanceTimersByTimeAsync(2000));
		// The second request is in flight: the session still reads as unavailable.
		screen.getByText("Session: unavailable");

		answer.resolve(HttpResponse.json(currentUser));
		await screen.findByText("Session: signed-in");
	} finally {
		vi.useRealTimers();
	}
});

it("reads a refusal after an unreachable server as signed out", async () => {
	vi.useFakeTimers({ shouldAdvanceTime: true });
	try {
		server.use(http.get("*/user", () => new HttpResponse(null, { status: 401 })));
		server.use(http.get("*/user", () => new HttpResponse(null, { status: 503 }), { once: true }));
		renderSession();
		await screen.findByText("Session: unavailable");

		await act(async () => vi.advanceTimersByTimeAsync(2000));
		await screen.findByText("Session: signed-out");
	} finally {
		vi.useRealTimers();
	}
});

it("reads a refused session as signed out and does not ask again", async () => {
	vi.useFakeTimers({ shouldAdvanceTime: true });
	try {
		const asked = vi.fn(() => new HttpResponse(null, { status: 401 }));
		server.use(http.get("*/user", asked));
		renderSession();
		await screen.findByText("Session: signed-out");

		await act(async () => vi.advanceTimersByTimeAsync(60_000));
		expect(asked).toHaveBeenCalledOnce();
	} finally {
		vi.useRealTimers();
	}
});
