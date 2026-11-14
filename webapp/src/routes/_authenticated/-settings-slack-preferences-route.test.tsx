import type { QueryClient } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, beforeAll, describe, expect, it } from "vitest";

import type {
	IdentityProviderView,
	IdentityView,
	SlackUserWorkspacePreferences,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { identityProviders, linkedIdentities } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { type Deferred, deferred } from "@/test/async";
import { captureNavigation, restoreNavigation } from "@/test/navigation";
import { loadRouteCode, renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/settings"), ROUTE_RENDER_WAIT.timeout);

afterEach(restoreNavigation);

const slackProvider: IdentityProviderView = {
	registrationId: "slack",
	displayName: "Slack",
	providerType: "SLACK",
};

const slackIdentity: Wire<IdentityView> = {
	id: 3,
	providerType: "SLACK",
	subject: "U0OCTO",
	displayName: "Octo in Slack",
};

const ALPHA: SlackUserWorkspacePreferences = {
	workspaceSlug: "alpha-lab",
	workspaceName: "Alpha Lab",
	slackTeamId: "T0ALPHA",
	slackTeamName: "Alpha Slack",
	channelMessagesAllowed: true,
	activeMonitoredChannelCount: 2,
};

const BETA: SlackUserWorkspacePreferences = {
	workspaceSlug: "beta-lab",
	workspaceName: "Beta Lab",
	slackTeamId: "T0BETA",
	slackTeamName: "Beta Slack",
	channelMessagesAllowed: false,
	activeMonitoredChannelCount: 1,
};

const ALPHA_OFF = { workspaceSlug: ALPHA.workspaceSlug, body: { channelMessagesAllowed: false } };
const BETA_ON = { workspaceSlug: BETA.workspaceSlug, body: { channelMessagesAllowed: true } };

const REFUSAL = "Slack did not accept this change. Try again.";

type Verdict = "accept" | "refuse";

/** One preference write as the server received it, answered when the test gives its verdict. */
interface SlackWrite {
	workspaceSlug: string;
	body: unknown;
	verdict: Deferred<Verdict>;
}

interface SlackServer {
	workspaces: SlackUserWorkspacePreferences[];
	writes: SlackWrite[];
	/** Set once the test is over: every write still out, and any that arrives later, is accepted. */
	released: boolean;
}

function allowedIn(body: unknown): boolean | undefined {
	return typeof body === "object" &&
		body !== null &&
		"channelMessagesAllowed" in body &&
		typeof body.channelMessagesAllowed === "boolean"
		? body.channelMessagesAllowed
		: undefined;
}

/** The server's answer to one write: it stores an accepted choice, so later reads return it. */
function answer(slack: SlackServer, write: SlackWrite, verdict: Verdict) {
	const allowed = allowedIn(write.body);
	const workspace = slack.workspaces.find(
		({ workspaceSlug }) => workspaceSlug === write.workspaceSlug,
	);
	if (allowed === undefined || workspace === undefined) {
		return HttpResponse.json(
			{ status: 400, title: "Bad Request", detail: "Not a Slack preference." },
			{ status: 400 },
		);
	}
	if (verdict === "refuse") {
		return HttpResponse.json({ status: 409, title: "Conflict", detail: REFUSAL }, { status: 409 });
	}
	workspace.channelMessagesAllowed = allowed;
	return HttpResponse.json(workspace);
}

/**
 * A linked Slack account with two workspaces, Alpha on and Beta off. The server holds every
 * preference write until the test settles it.
 */
function serveSlack(): SlackServer {
	const slack: SlackServer = {
		workspaces: [{ ...ALPHA }, { ...BETA }],
		writes: [],
		released: false,
	};
	server.use(
		http.get("*/identity-providers", () =>
			HttpResponse.json([...identityProviders, slackProvider]),
		),
		http.get("*/user/identities", () => HttpResponse.json([...linkedIdentities, slackIdentity])),
		http.get("*/user/slack/preferences", () => HttpResponse.json({ workspaces: slack.workspaces })),
		http.patch<{ workspaceSlug: string }>(
			"*/workspaces/:workspaceSlug/slack/me/preferences",
			async ({ params, request }) => {
				const write: SlackWrite = {
					workspaceSlug: params.workspaceSlug,
					body: await request.json(),
					verdict: deferred<Verdict>(),
				};
				slack.writes.push(write);
				if (slack.released) {
					write.verdict.resolve("accept");
				}
				return answer(slack, write, await write.verdict.promise);
			},
		),
	);
	return slack;
}

/** Answers every write the server holds for `workspace`. One already answered keeps its answer. */
function settle(slack: SlackServer, workspace: SlackUserWorkspacePreferences, verdict: Verdict) {
	for (const write of slack.writes) {
		if (write.workspaceSlug === workspace.workspaceSlug) {
			write.verdict.resolve(verdict);
		}
	}
}

/**
 * Accepts every write still held, and any that arrives late, then waits until the page has seen
 * each answer. Runs when an assertion fails too, so no request or toast outlives the test.
 */
async function releaseEveryWrite(slack: SlackServer, queryClient: QueryClient) {
	slack.released = true;
	for (const write of slack.writes) {
		write.verdict.resolve("accept");
	}
	await waitFor(() => expect(queryClient.isMutating()).toBe(0));
}

function sent(slack: SlackServer) {
	return slack.writes.map(({ workspaceSlug, body }) => ({ workspaceSlug, body }));
}

function channelMessagesSwitch(workspace: SlackUserWorkspacePreferences) {
	const group = screen.getByRole("group", { name: `${workspace.workspaceName} Slack preferences` });
	return within(group).getByRole("switch", { name: "Use your new channel messages" });
}

/** The workspace's own write is out: its switch takes no new choice and says it is busy. */
function expectWriting(workspace: SlackUserWorkspacePreferences) {
	const control = channelMessagesSwitch(workspace);
	expect(control.getAttribute("aria-disabled")).toBe("true");
	expect(control.getAttribute("aria-busy")).toBe("true");
}

function expectSettled(workspace: SlackUserWorkspacePreferences, allowed: boolean) {
	const control = channelMessagesSwitch(workspace);
	expect(control.getAttribute("aria-checked")).toBe(String(allowed));
	expect(control.getAttribute("aria-disabled")).not.toBe("true");
	expect(control.getAttribute("aria-busy")).not.toBe("true");
}

async function confirmStop(user: UserEvent) {
	const confirmation = await screen.findByRole("alertdialog");
	await user.click(within(confirmation).getByRole("button", { name: "Stop using and delete" }));
	await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
}

/** Turns Alpha off, confirmed, and then Beta on, while the server holds both writes. */
async function startBothWrites(user: UserEvent, slack: SlackServer) {
	await screen.findByRole(
		"group",
		{ name: `${ALPHA.workspaceName} Slack preferences` },
		ROUTE_RENDER_WAIT,
	);
	await user.click(channelMessagesSwitch(ALPHA));
	await confirmStop(user);
	await waitFor(() => expect(sent(slack)).toStrictEqual([ALPHA_OFF]));
	await waitFor(() => expectWriting(ALPHA));

	await user.click(channelMessagesSwitch(BETA));
	await waitFor(() => expect(sent(slack)).toStrictEqual([ALPHA_OFF, BETA_ON]));
	await waitFor(() => expectWriting(BETA));
	// Beta's busy switch is on screen, so this commit also shows Alpha's write, which is still out.
	expectWriting(ALPHA);
}

describe("Slack channel-message choices on the settings page", () => {
	it.each(["/settings", "/settings?chromeWorkspace=beta#slack"])(
		"keeps the current settings destination when connecting Slack from %s",
		async (returnTo) => {
			server.use(
				http.get("*/identity-providers", () =>
					HttpResponse.json([...identityProviders, slackProvider]),
				),
				http.get("*/user/identities", () => HttpResponse.json(linkedIdentities)),
				http.get("*/user/slack/preferences", () => HttpResponse.json({ workspaces: [] })),
			);
			renderRouteAt(returnTo);
			const region = await screen.findByRole("region", { name: "Slack" }, ROUTE_RENDER_WAIT);
			const connect = await within(region).findByRole("button", { name: "Connect Slack" });
			const assigned = captureNavigation();

			await userEvent.click(connect);

			await waitFor(() => expect(assigned).toHaveLength(1));
			const [target] = assigned;
			assert(target !== undefined);
			const authorization = new URL(target);
			expect(authorization.origin + authorization.pathname).toBe(
				"http://localhost:8080/auth/login",
			);
			expect(authorization.searchParams.get("provider")).toBe("slack");
			expect(authorization.searchParams.get("mode")).toBe("link");
			expect(authorization.searchParams.get("returnTo")).toBe(returnTo);
		},
	);

	it("keeps a workspace's write pending after a later write to another workspace finishes first", async () => {
		const user = userEvent.setup();
		const slack = serveSlack();
		const queryClient = renderRouteAt("/settings");
		try {
			await startBothWrites(user, slack);

			settle(slack, BETA, "accept");
			await screen.findByText("Slack channel-message use is on.");
			await waitFor(() => expectSettled(BETA, true));
			expectWriting(ALPHA);
			expect(channelMessagesSwitch(ALPHA).getAttribute("aria-checked")).toBe("true");

			// Alpha's write is still out, so its switch asks for no second confirmation.
			await user.click(channelMessagesSwitch(ALPHA));
			expect(screen.queryByRole("alertdialog")).toBeNull();
			// Beta takes a new choice: its confirmation opens, and the reader can cancel it.
			await user.click(channelMessagesSwitch(BETA));
			const confirmation = await screen.findByRole("alertdialog");
			await user.click(within(confirmation).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

			settle(slack, ALPHA, "accept");
			await screen.findByText("Slack channel-message use is off.");
			await waitFor(() => expectSettled(ALPHA, false));
			expectSettled(BETA, true);
			expect(sent(slack)).toStrictEqual([ALPHA_OFF, BETA_ON]);
		} finally {
			await releaseEveryWrite(slack, queryClient);
		}
	});

	it("keeps the old choice and a retry when a held write is refused after another finishes", async () => {
		const user = userEvent.setup();
		const slack = serveSlack();
		const queryClient = renderRouteAt("/settings");
		try {
			await startBothWrites(user, slack);

			settle(slack, BETA, "accept");
			await waitFor(() => expectSettled(BETA, true));
			expectWriting(ALPHA);

			settle(slack, ALPHA, "refuse");
			await screen.findByText(REFUSAL);
			await waitFor(() => expectSettled(ALPHA, true));
			expectSettled(BETA, true);

			await user.click(channelMessagesSwitch(ALPHA));
			await confirmStop(user);
			await waitFor(() => expect(sent(slack)).toStrictEqual([ALPHA_OFF, BETA_ON, ALPHA_OFF]));
			await waitFor(() => expectWriting(ALPHA));

			settle(slack, ALPHA, "accept");
			await waitFor(() => expectSettled(ALPHA, false));
			expectSettled(BETA, true);
			expect(sent(slack)).toStrictEqual([ALPHA_OFF, BETA_ON, ALPHA_OFF]);
		} finally {
			await releaseEveryWrite(slack, queryClient);
		}
	});

	it("keeps the later write pending when the earlier one finishes first", async () => {
		const user = userEvent.setup();
		const slack = serveSlack();
		const queryClient = renderRouteAt("/settings");
		try {
			await startBothWrites(user, slack);

			settle(slack, ALPHA, "accept");
			await screen.findByText("Slack channel-message use is off.");
			await waitFor(() => expectSettled(ALPHA, false));
			expectWriting(BETA);
			expect(channelMessagesSwitch(BETA).getAttribute("aria-checked")).toBe("false");

			settle(slack, BETA, "accept");
			await waitFor(() => expectSettled(BETA, true));
			expectSettled(ALPHA, false);
			expect(sent(slack)).toStrictEqual([ALPHA_OFF, BETA_ON]);
		} finally {
			await releaseEveryWrite(slack, queryClient);
		}
	});
});
