import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { browser } from "@wxt-dev/browser";
import { CircleAlertIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import { HephaestusLogo } from "~/components/brand/HephaestusLogo";
import { Button } from "~/components/common/Button";
import { Card } from "~/components/common/Card";
import { Notice } from "~/components/common/Notice";
import { Skeleton } from "~/components/common/Skeleton";
import { AccountSummary } from "~/components/options/AccountSummary";
import { ConsentPrompt } from "~/components/options/ConsentPrompt";
import { InstanceCard } from "~/components/options/InstanceCard";
import { OptionsFooter } from "~/components/options/OptionsFooter";
import { SetupSteps } from "~/components/options/SetupSteps";
import {
	type SignInAttempt,
	SignInPanel,
	type SignInOptionsState,
} from "~/components/options/SignInPanel";
import {
	type SiteAccessState,
	type SiteActivity,
	SiteAccessList,
} from "~/components/options/SiteAccessList";
import { UsageGuide } from "~/components/options/UsageGuide";
import {
	type ConnectState,
	type ConnectTarget,
	WelcomeCard,
} from "~/components/options/WelcomeCard";
import {
	DOCS_ORIGIN,
	EXTENSION_HELP_URL,
	EXTENSION_PRIVACY_URL,
	HOSTED_INSTANCE_ORIGIN,
	isHostedInstance,
} from "~/shared/hosted";
import {
	INSTANCE_ORIGIN_MESSAGES,
	originPattern,
	parseInstanceOrigin,
} from "~/shared/instance-url";
import type { AppState, InstanceSummary } from "~/shared/rpc";
import { ask, RpcClientError } from "~/ui/rpc-client";
import { useGeneration } from "~/ui/worker-state";

const HOSTED_HOST = new URL(HOSTED_INSTANCE_ORIGIN).host;

function messageOf(error: unknown, fallback: string): string {
	return error instanceof Error && error.message !== "" ? error.message : fallback;
}

/**
 * Choosing an instance. Chrome shows its permission prompt only for a request made inside the click,
 * so asking is the first thing the handler awaits; the worker then checks the address answers as a
 * Hephaestus instance before anything is stored.
 */
function Setup({ developmentBuild }: { developmentBuild: boolean }) {
	const [state, setState] = useState<ConnectState>({ status: "idle" });
	const connect = (target: ConnectTarget, input: { origin: string; webAppOrigin?: string }) => {
		const parsed = parseInstanceOrigin(input.origin, { allowLoopbackHttp: developmentBuild });
		if (!parsed.ok) {
			setState({ status: "error", target, message: INSTANCE_ORIGIN_MESSAGES[parsed.reason] });
			return;
		}
		setState({ status: "pending", target });
		const { host } = new URL(parsed.origin);
		const run = async () => {
			try {
				const granted = await browser.permissions.request({
					origins: [originPattern(parsed.origin)],
				});
				if (!granted) {
					setState({
						status: "error",
						target,
						message: `Chrome did not allow the extension to reach ${host}, so nothing changed. Try again to see Chrome's prompt.`,
					});
					return;
				}
				await ask({
					type: "configure-instance",
					origin: parsed.origin,
					webAppOrigin: input.webAppOrigin,
				});
				setState({ status: "idle" });
			} catch (error) {
				setState({ status: "error", target, message: messageOf(error, "Connecting failed.") });
			}
		};
		void run();
	};
	return (
		<WelcomeCard
			hostedHost={HOSTED_HOST}
			developmentBuild={developmentBuild}
			state={state}
			onConnectHosted={() => connect("hosted", { origin: HOSTED_INSTANCE_ORIGIN })}
			onConnectCustom={(input) => connect("custom", input)}
			privacyUrl={EXTENSION_PRIVACY_URL}
			docsOrigin={DOCS_ORIGIN}
		/>
	);
}

function SignIn({ instance }: { instance: InstanceSummary }) {
	const options = useQuery({
		queryKey: ["sign-in-options"],
		queryFn: async () => ask({ type: "list-sign-in-options" }),
	});
	const signIn = useMutation({
		mutationFn: async (method: { registrationId: string } | { username: string; admin: boolean }) =>
			ask(
				"registrationId" in method
					? { type: "sign-in", registrationId: method.registrationId }
					: { type: "sign-in-dev", username: method.username, admin: method.admin },
			),
	});
	let optionsState: SignInOptionsState = { status: "loading" };
	if (options.isError) {
		optionsState = {
			status: "error",
			message: options.error.message,
			onRetry: () => {
				void options.refetch();
			},
		};
	} else if (options.data !== undefined) {
		optionsState = { status: "ready", options: options.data };
	}
	let attempt: SignInAttempt = { status: "idle" };
	if (signIn.isPending) {
		attempt = {
			status: "pending",
			registrationId:
				"registrationId" in signIn.variables ? signIn.variables.registrationId : "dev",
		};
	} else if (signIn.error instanceof RpcClientError && signIn.error.code === "cancelled") {
		attempt = { status: "cancelled" };
	} else if (signIn.isError) {
		attempt = { status: "failed", message: signIn.error.message };
	}
	return (
		<Card
			title={`Sign in to ${instance.host}`}
			description="Use the account you already use in Hephaestus."
		>
			<SignInPanel
				instanceHost={instance.host}
				options={optionsState}
				attempt={attempt}
				onSignIn={(registrationId) => signIn.mutate({ registrationId })}
				onDevSignIn={(username, admin) => signIn.mutate({ username, admin })}
			/>
		</Card>
	);
}

function useSiteAccess() {
	const queryClient = useQueryClient();
	const [activity, setActivity] = useState<SiteActivity>();
	const sites = useQuery({
		queryKey: ["site-access"],
		queryFn: async () => ask({ type: "list-site-access" }),
	});
	const refresh = async () => queryClient.invalidateQueries({ queryKey: ["site-access"] });
	let state: SiteAccessState = { status: "loading" };
	if (sites.isError) {
		state = {
			status: "error",
			message: sites.error.message,
			onRetry: () => {
				void sites.refetch();
			},
		};
	} else if (sites.data !== undefined) {
		state = { status: "ready", entries: sites.data };
	}
	const change = (origin: string, grant: boolean) => {
		setActivity({ origin, status: "pending" });
		// The request is the first thing the click awaits, so Chrome shows its prompt.
		const request = grant
			? browser.permissions.request({ origins: [originPattern(origin)] })
			: browser.permissions.remove({ origins: [originPattern(origin)] });
		const settle = async () => {
			try {
				const changed = await request;
				setActivity(grant && !changed ? { origin, status: "denied" } : undefined);
			} catch (error) {
				setActivity({
					origin,
					status: "error",
					message: messageOf(error, "Chrome could not change the extension's access."),
				});
			}
			await refresh();
		};
		void settle();
	};
	return {
		state,
		activity,
		anyAllowed: sites.data?.some((entry) => entry.granted) === true,
		onGrant: (origin: string) => change(origin, true),
		onRevoke: (origin: string) => change(origin, false),
	};
}

function InstanceSection({ instance }: { instance: InstanceSummary }) {
	const clear = useMutation({ mutationFn: async () => ask({ type: "clear-instance" }) });
	return (
		<Card title="Hephaestus instance">
			<InstanceCard
				host={instance.host}
				hosted={isHostedInstance(instance.origin)}
				disconnecting={clear.isPending}
				onDisconnect={() => clear.mutate()}
			/>
			{clear.isError ? (
				<p className="text-sm text-destructive" role="alert">
					{clear.error.message}
				</p>
			) : null}
		</Card>
	);
}

function SignedIn({
	session,
	instance,
}: {
	session: Extract<AppState["session"], { status: "signed-in" }>;
	instance: InstanceSummary;
}) {
	const signOut = useMutation({ mutationFn: async () => ask({ type: "sign-out" }) });
	const sites = useSiteAccess();
	const settled = sites.state.status === "ready";
	return (
		<>
			{settled && !sites.anyAllowed ? <SetupSteps current="sites" /> : null}
			<Card title="Account">
				<AccountSummary
					account={session.account}
					instanceHost={instance.host}
					sessionExpiresAt={session.sessionExpiresAt}
					signingOut={signOut.isPending}
					onSignOut={() => signOut.mutate()}
				/>
			</Card>
			<Card
				title="Sites"
				description={
					<>
						A practice review appears on pull requests, merge requests and issues only on the sites
						you allow. There, as soon as you open one, the extension sends its address to{" "}
						{instance.host} to look it up; on a list, only the row you press the Hephaestus button
						on. Never the page&apos;s content. Remove a site to stop.
					</>
				}
			>
				<SiteAccessList
					state={sites.state}
					activity={sites.activity}
					webAppOrigin={instance.webAppOrigin}
					onGrant={sites.onGrant}
					onRevoke={sites.onRevoke}
				/>
			</Card>
			{sites.anyAllowed ? (
				<Card title="Where to find it">
					<UsageGuide />
				</Card>
			) : null}
			<InstanceSection instance={instance} />
		</>
	);
}

function Connected({ state, instance }: { state: AppState; instance: InstanceSummary }) {
	const queryClient = useQueryClient();
	const signOut = useMutation({ mutationFn: async () => ask({ type: "sign-out" }) });
	const [checking, setChecking] = useState(false);
	const { session } = state;
	switch (session.status) {
		case "signed-in": {
			return <SignedIn session={session} instance={instance} />;
		}
		case "consent-required": {
			return (
				<>
					<SetupSteps current="sign-in" />
					<ConsentPrompt
						instanceHost={instance.host}
						webAppOrigin={instance.webAppOrigin}
						checking={checking}
						onCheckAgain={() => {
							setChecking(true);
							const check = async () => {
								await queryClient.refetchQueries({ queryKey: ["state"] });
								setChecking(false);
							};
							void check();
						}}
					/>
					<Card title="Account">
						{session.account === undefined ? (
							<div>
								<Button
									variant="outline"
									disabled={signOut.isPending}
									onClick={() => signOut.mutate()}
								>
									Sign out
								</Button>
							</div>
						) : (
							<AccountSummary
								account={session.account}
								instanceHost={instance.host}
								signingOut={signOut.isPending}
								onSignOut={() => signOut.mutate()}
							/>
						)}
					</Card>
					<InstanceSection instance={instance} />
				</>
			);
		}
		case "signed-out": {
			return (
				<>
					<SetupSteps current="sign-in" />
					<SignIn instance={instance} />
					<InstanceSection instance={instance} />
				</>
			);
		}
	}
}

function manifestVersion(): string | undefined {
	try {
		return browser.runtime.getManifest().version;
	} catch {
		return undefined;
	}
}

/** Setup, sign-in, site access and sign-out: everything that changes what the extension may do. */
export function OptionsView() {
	const generation = useGeneration();
	const state = useQuery({ queryKey: ["state"], queryFn: async () => ask({ type: "get-state" }) });
	let body: ReactNode = (
		<div className="flex flex-col gap-3" aria-hidden>
			<Skeleton className="h-40 w-full rounded-xl" />
			<Skeleton className="h-24 w-full rounded-xl" />
		</div>
	);
	if (state.isError) {
		body = (
			<Notice
				icon={CircleAlertIcon}
				tone="destructive"
				title="The extension did not answer"
				action={
					<Button
						variant="outline"
						size="sm"
						onClick={() => {
							void state.refetch();
						}}
					>
						Try again
					</Button>
				}
			>
				{state.error.message}
			</Notice>
		);
	} else if (state.data?.instance === undefined && state.data !== undefined) {
		body = (
			<>
				<SetupSteps current="connect" />
				<Setup developmentBuild={state.data.developmentBuild} />
			</>
		);
	} else if (state.data?.instance !== undefined) {
		body = <Connected state={state.data} instance={state.data.instance} />;
	}
	return (
		<div className="min-h-screen bg-muted/40 text-foreground">
			<div className="mx-auto flex max-w-2xl flex-col gap-6 px-5 py-8 sm:py-12">
				<header className="flex items-center justify-between gap-4">
					<HephaestusLogo />
					<h1 className="sr-only">Hephaestus for Chrome settings</h1>
				</header>
				<main className="flex flex-col gap-5" key={generation}>
					{body}
				</main>
				<OptionsFooter
					helpUrl={EXTENSION_HELP_URL}
					privacyUrl={EXTENSION_PRIVACY_URL}
					docsOrigin={DOCS_ORIGIN}
					version={manifestVersion()}
				/>
			</div>
		</div>
	);
}
