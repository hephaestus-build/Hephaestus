import { CircleAlertIcon, InfoIcon, LogInIcon, ShieldAlertIcon } from "lucide-react";
import { type ReactNode, type SubmitEvent, useId, useState } from "react";

import { GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { Button } from "~/components/common/Button";
import { Notice } from "~/components/common/Notice";
import { Skeleton } from "~/components/common/Skeleton";
import { Spinner } from "~/components/common/Spinner";
import type { SignInOption, SignInOptions } from "~/shared/rpc";

export type SignInOptionsState =
	| { status: "loading" }
	| { status: "error"; message: string; onRetry: () => void }
	| { status: "ready"; options: SignInOptions };

export type SignInAttempt =
	| { status: "idle" }
	| { status: "pending"; registrationId: string }
	/** The reader closed the sign-in window: nothing changed, and nothing went wrong. */
	| { status: "cancelled" }
	| { status: "failed"; message: string };

export interface SignInPanelProps {
	instanceHost: string;
	options: SignInOptionsState;
	attempt: SignInAttempt;
	onSignIn: (registrationId: string) => void;
	onDevSignIn: (username: string, admin: boolean) => void;
}

function ProviderIcon({ option }: { option: SignInOption }): ReactNode {
	switch (option.providerType.toUpperCase()) {
		case "GITHUB": {
			return <GithubIcon aria-hidden="true" aria-label="GitHub" />;
		}
		case "GITLAB": {
			return <GitlabIcon aria-hidden="true" aria-label="GitLab" />;
		}
		default: {
			return <LogInIcon aria-hidden />;
		}
	}
}

function DevSignIn({
	pending,
	onDevSignIn,
}: {
	pending: boolean;
	onDevSignIn: SignInPanelProps["onDevSignIn"];
}) {
	const [username, setUsername] = useState("");
	const [admin, setAdmin] = useState(false);
	const usernameId = useId();
	const submit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		onDevSignIn(username, admin);
	};
	return (
		<form
			className="flex flex-col gap-2 rounded-lg border border-dashed border-border p-3"
			onSubmit={submit}
		>
			<p className="text-xs text-muted-foreground">
				Development sign-in: this server lets anyone sign in by name.
			</p>
			<label htmlFor={usernameId} className="text-sm font-medium">
				User name
			</label>
			<input
				id={usernameId}
				value={username}
				required
				pattern="[A-Za-z0-9_.\-]{1,39}"
				onChange={(event) => setUsername(event.target.value)}
				className="h-9 rounded-lg border border-input bg-background px-3 text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
			/>
			<label className="flex items-center gap-2 text-sm">
				<input
					type="checkbox"
					checked={admin}
					onChange={(event) => setAdmin(event.target.checked)}
					className="size-4 accent-(--mentor)"
				/>
				Instance admin
			</label>
			<div>
				<Button type="submit" variant="outline" disabled={pending}>
					{pending ? <Spinner /> : null}
					Sign in as developer
				</Button>
			</div>
		</form>
	);
}

/**
 * One button per way in the instance offers. Slack and Outline are only ever linked to an account
 * that exists, so they are not offered here. A server that has not registered this extension's id is
 * told apart from every other failure, with the id an operator needs.
 */
export function SignInPanel({
	instanceHost,
	options,
	attempt,
	onSignIn,
	onDevSignIn,
}: SignInPanelProps) {
	if (options.status === "loading") {
		return (
			<div className="flex flex-col gap-2" aria-hidden>
				<Skeleton className="h-10 w-full max-w-sm" />
				<Skeleton className="h-10 w-full max-w-sm" />
			</div>
		);
	}
	if (options.status === "error") {
		return (
			<Notice
				icon={CircleAlertIcon}
				tone="destructive"
				title="Sign-in options could not be loaded"
				action={
					<Button variant="outline" size="sm" onClick={options.onRetry}>
						Try again
					</Button>
				}
			>
				{options.message}
			</Notice>
		);
	}
	const { options: available } = options;
	if (!available.registered) {
		return (
			<Notice
				icon={ShieldAlertIcon}
				tone="warning"
				title="This instance does not know this extension"
			>
				{instanceHost} has not registered this extension, so it cannot sign you in. Ask its operator
				to add the extension id <code className="font-mono break-all">{available.extensionId}</code>{" "}
				to the instance&apos;s browser extension ids.
			</Notice>
		);
	}
	const pendingId = attempt.status === "pending" ? attempt.registrationId : undefined;
	return (
		<div className="flex flex-col gap-4">
			{available.options.length === 0 && !available.devSignIn ? (
				<p className="text-sm text-muted-foreground">{instanceHost} offers no way to sign in.</p>
			) : null}
			{available.options.length === 0 ? null : (
				<div className="flex w-full max-w-sm flex-col gap-2">
					{available.options.map((option) => (
						<Button
							key={option.registrationId}
							variant="outline"
							size="lg"
							className="justify-start"
							disabled={pendingId !== undefined}
							onClick={() => onSignIn(option.registrationId)}
						>
							{pendingId === option.registrationId ? <Spinner /> : <ProviderIcon option={option} />}
							Sign in with {option.displayName}
						</Button>
					))}
				</div>
			)}
			{available.devSignIn ? (
				<DevSignIn pending={pendingId !== undefined} onDevSignIn={onDevSignIn} />
			) : null}
			{attempt.status === "cancelled" ? (
				<p className="flex items-start gap-2 text-sm text-muted-foreground" role="status">
					<InfoIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
					Sign-in was cancelled and nothing changed. Choose a way to sign in to try again.
				</p>
			) : null}
			{attempt.status === "failed" ? (
				<p className="flex items-start gap-2 text-sm text-destructive" role="alert">
					<CircleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
					{attempt.message}
				</p>
			) : null}
			<p className="text-xs text-muted-foreground">
				A sign-in window from {instanceHost} opens and closes by itself. You stay signed in until
				you close Chrome, or at most 7 days; your instance may end it sooner.
			</p>
		</div>
	);
}
