import { Link } from "@tanstack/react-router";
import { FingerprintIcon, KeyRoundIcon } from "lucide-react";

import { StepMarker } from "@/components/auth/StepMarker";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

export type PasskeyChallengeState =
	| { status: "loading" }
	| { status: "error"; message: string; onRetry: () => void }
	| {
			status: "ready";
			/** `add` while the account has no passkey; the server needs a confirmation either way. */
			step: "add" | "confirm";
			/** A passkey was saved in this visit, so the confirmation is the second of two steps. */
			justAdded: boolean;
			/** Recovery removed the old passkeys. */
			recovering: boolean;
			supported: boolean;
			pending?: "add" | "confirm";
			error?: string;
	  };

export interface PasskeyChallengePageProps {
	state: PasskeyChallengeState;
	onAdd: () => void;
	onConfirm: () => void;
	/** The one way out without confirming, chosen by the route. */
	onLeave: () => void;
}

/**
 * Where a developer lands when the server asks for a recent passkey before an administration page or
 * action. It states the task, offers the one next step, and leaves without one. The page they came
 * from takes them back once the server accepts the confirmation.
 */
export function PasskeyChallengePage(props: PasskeyChallengePageProps) {
	return (
		<div className="flex min-h-svh flex-col items-center justify-center gap-6 bg-background p-6">
			{/* Brand only: leaving is "Not now", so the page has one exit that behaves one way. */}
			<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />
			<Card className="w-full max-w-sm" aria-busy={props.state.status === "loading"}>
				<ChallengeBody {...props} />
			</Card>
		</div>
	);
}

function ChallengeBody({ state, onAdd, onConfirm, onLeave }: PasskeyChallengePageProps) {
	const pending = state.status === "ready" ? state.pending : undefined;
	const notNow = (
		<Button variant="quiet" className="w-full" onClick={onLeave}>
			Not now
		</Button>
	);

	if (state.status === "loading") {
		return (
			<>
				<CardHeader className="space-y-2">
					<Skeleton className="h-6 w-48" />
					<Skeleton className="h-4 w-full" />
					<Skeleton className="h-4 w-3/4" />
				</CardHeader>
				<CardContent className="space-y-3">
					<Skeleton className="h-9 w-full" />
					<Skeleton className="mx-auto h-4 w-16" />
				</CardContent>
			</>
		);
	}

	if (state.status === "error") {
		return (
			<>
				<CardHeader>
					<h1 className="text-xl font-semibold tracking-tight">Confirm with a passkey</h1>
				</CardHeader>
				<CardContent className="space-y-4">
					<Alert variant="destructive">
						<AlertTitle>We could not check your passkeys</AlertTitle>
						<AlertDescription>{state.message}</AlertDescription>
					</Alert>
					<div className="flex flex-col gap-2">
						<Button onClick={state.onRetry}>Try again</Button>
						{notNow}
					</div>
				</CardContent>
			</>
		);
	}

	const { step, justAdded, recovering, supported, error } = state;
	const twoSteps = step === "add" || justAdded;
	let title = "Confirm with your passkey";
	let description =
		"Confirm it’s you to continue. Your browser asks for your fingerprint, face, screen lock or security key.";
	if (step === "add") {
		title = "Add a passkey to continue";
		description = recovering
			? "Recovery removed your earlier passkeys. Add a new one on this device, then confirm with it to continue."
			: "Confirm it’s you to continue: add a passkey on this device, then confirm with it.";
	} else if (justAdded) {
		description = "Your passkey is saved. Confirm with it once to continue.";
	}

	return (
		<>
			<CardHeader className="space-y-2">
				<h1 className="text-xl font-semibold tracking-tight">{title}</h1>
				<p className="text-sm text-muted-foreground">{description}</p>
			</CardHeader>
			<CardContent>
				{/* The one announcer of a failed attempt. It exists before an error lands in it and stays
				    in the accessibility tree while empty; the alert inside gives up its own role so the
				    message is announced once. Margins rather than `space-y`, so its empty box claims no
				    slot. */}
				<div aria-live="assertive" aria-atomic="true" className="not-empty:mb-4">
					{error !== undefined && (
						<Alert role="none" variant="destructive">
							<AlertTitle>Not confirmed yet</AlertTitle>
							<AlertDescription>{error}</AlertDescription>
						</Alert>
					)}
				</div>
				<div className="space-y-4">
					{twoSteps && (
						<ol aria-label="Steps" className="space-y-2 text-sm">
							<li className="flex items-center gap-3">
								<StepMarker icon={KeyRoundIcon} done={step === "confirm"} />
								<span>
									Add a passkey
									{step === "confirm" && <span className="sr-only"> (done)</span>}
								</span>
							</li>
							<li className="flex items-center gap-3">
								<StepMarker icon={FingerprintIcon} done={false} />
								<span>Confirm it’s you</span>
							</li>
						</ol>
					)}
					{supported ? (
						// Focusable while the browser prompt is open, so focus returns here when it closes;
						// a pending button refuses a second press, so one prompt runs at a time.
						<Button
							pending={pending !== undefined}
							className="w-full"
							size="lg"
							aria-busy={pending !== undefined}
							onClick={step === "add" ? onAdd : onConfirm}
						>
							{pendingLabel(pending) ?? (step === "add" ? "Add passkey" : "Confirm with passkey")}
						</Button>
					) : (
						<Alert role="none">
							<AlertTitle>This browser cannot use passkeys</AlertTitle>
							<AlertDescription>
								Open Hephaestus in a current browser on HTTPS, on a device that can hold a passkey.
							</AlertDescription>
						</Alert>
					)}
					{notNow}
					{step === "confirm" && (
						<details className="text-sm text-muted-foreground">
							<summary className="cursor-pointer">Cannot use your passkey?</summary>
							<p className="mt-2">
								Use another device that has one of your passkeys, or start recovery with a recovery
								code.{" "}
								<Link to="/settings" hash="passkeys" className="text-foreground underline">
									Open passkey settings
								</Link>
							</p>
						</details>
					)}
				</div>
			</CardContent>
		</>
	);
}

function pendingLabel(pending: "add" | "confirm" | undefined): string | undefined {
	if (pending === "add") {
		return "Saving your passkey…";
	}
	if (pending === "confirm") {
		return "Waiting for your passkey…";
	}
	return undefined;
}
