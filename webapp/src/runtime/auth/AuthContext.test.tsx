import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { useState } from "react";
import { afterEach, beforeEach, expect, it } from "vitest";

import { server } from "@/mocks/server";
import { clearUserView, getUserViewSession } from "@/runtime/user-view/session";
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

	expect(getUserViewSession()?.userId).toBe(11);
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
