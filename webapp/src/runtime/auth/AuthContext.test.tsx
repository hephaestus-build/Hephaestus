import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { toast } from "sonner";
import { afterEach, expect, it, vi } from "vitest";

import { applyUserViewHeaders, getUserViewSession } from "@/runtime/user-view/session";

import { authClient } from "./auth-client";
import { AuthProvider, useAuth } from "./AuthContext";

vi.mock("@tanstack/react-query", async (importOriginal) => ({
	...(await importOriginal()),
	useQuery: () => ({
		data: { id: 7, username: "administrator", roles: [], appRole: "APP_ADMIN" },
		isPending: false,
		isError: false,
	}),
}));

afterEach(() => {
	sessionStorage.clear();
	vi.restoreAllMocks();
});

function SignOut() {
	const { logout, username } = useAuth();
	return (
		<>
			<span>{username}</span>
			<button
				type="button"
				onClick={() => {
					logout().catch(() => undefined);
				}}
			>
				Sign out
			</button>
		</>
	);
}

it("keeps the viewed identity when sign-out fails", async () => {
	sessionStorage.setItem(
		"hephaestus.user-view",
		JSON.stringify({
			operatorAccountId: 7,
			workspaceSlug: "acme",
			userId: 42,
			login: "alex",
			name: "Alex",
			hasAccount: false,
			reason: "Check feedback",
		}),
	);
	vi.spyOn(authClient, "logout").mockRejectedValue(new Error("Network unavailable"));
	const error = vi.spyOn(toast, "error").mockReturnValue("");

	render(
		<AuthProvider>
			<SignOut />
		</AuthProvider>,
	);
	fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
	await waitFor(() =>
		expect(error).toHaveBeenCalledWith("Could not confirm sign-out. Please try again."),
	);
	expect(getUserViewSession()?.userId).toBe(42);
	expect(screen.getByText("alex")).not.toBeNull();
	expect(
		applyUserViewHeaders(
			new Request("https://example.test/workspaces/acme/mentor/threads"),
		).headers.get("X-User-View-User"),
	).toBe("42");
});
