import { Link } from "@tanstack/react-router";
import { useSpinDelay } from "spin-delay";

import { Button, buttonVariants } from "@/components/ui/button";
import {
	Card,
	CardContent,
	CardDescription,
	CardFooter,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";

export type GitHubInstallationSetupState =
	/** Signed in, with GitHub's code: Continue asks Hephaestus to confirm the installation with GitHub. */
	| { status: "confirm"; onConfirm: () => void }
	/** The check runs; nothing more can be sent until it answers. */
	| { status: "pending" }
	/** GitHub's code works once, so a failed check cannot be repeated with it. */
	| { status: "error"; message: string }
	/** Under the admin-only creation policy, an instance admin adopts each installation. */
	| { status: "awaiting-approval"; canApprove: boolean }
	/** The installation creates its own workspace, and that has not happened yet. */
	| { status: "setting-up" }
	| { status: "signed-out"; requiresApproval: boolean; onSignIn: () => void }
	| { status: "invalid" };

const APPROVAL_NOTICE =
	"An instance administrator must approve this installation before Hephaestus can use it.";

function copyOf(state: GitHubInstallationSetupState): { title: string; description: string } {
	switch (state.status) {
		case "confirm":
		case "pending": {
			return {
				title: "Finish installing the GitHub App",
				description: "Hephaestus checks with GitHub that you installed the app on this account.",
			};
		}
		case "error": {
			return {
				title: "We could not confirm the installation",
				description: "To try again, install the app again from GitHub.",
			};
		}
		case "awaiting-approval": {
			return { title: "Waiting for approval", description: APPROVAL_NOTICE };
		}
		case "setting-up": {
			return {
				title: "Setting up your workspace",
				description:
					"Hephaestus creates the workspace for this installation. It appears in your sidebar shortly.",
			};
		}
		case "signed-out": {
			return state.requiresApproval
				? { title: "Waiting for approval", description: APPROVAL_NOTICE }
				: {
						title: "Sign in to see your workspace",
						description: "Hephaestus creates the workspace for this installation.",
					};
		}
		case "invalid": {
			return {
				title: "This link is incomplete",
				description:
					"GitHub did not send the confirmation that Hephaestus needs. Install the app again from GitHub.",
			};
		}
	}
}

/** Where GitHub sends a person after they install the GitHub App on an account. */
export function GitHubInstallationSetupPage({ state }: { state: GitHubInstallationSetupState }) {
	const showSpinner = useSpinDelay(state.status === "pending", { delay: 300, minDuration: 500 });
	const { title, description } = copyOf(state);

	return (
		<div className="mx-auto flex min-h-svh w-full max-w-lg items-center p-4">
			<Card className="w-full">
				<CardHeader>
					<CardTitle>
						<h1>{title}</h1>
					</CardTitle>
					<CardDescription aria-live="polite" aria-atomic="true">
						{description}
					</CardDescription>
				</CardHeader>
				{state.status === "error" && (
					<CardContent>
						<p role="alert" className="text-sm text-destructive">
							{state.message}
						</p>
					</CardContent>
				)}
				<CardFooter className="gap-2">
					{state.status === "confirm" && <Button onClick={state.onConfirm}>Continue</Button>}
					{state.status === "pending" && (
						<Button disabled>
							{showSpinner && <Spinner />}
							Checking…
						</Button>
					)}
					{state.status === "awaiting-approval" && state.canApprove && (
						<Link to="/admin/github-installations" className={buttonVariants()}>
							Review installations
						</Link>
					)}
					{state.status === "signed-out" && <Button onClick={state.onSignIn}>Sign in</Button>}
					{state.status !== "confirm" &&
						state.status !== "pending" &&
						state.status !== "signed-out" && (
							<Link to="/" className={buttonVariants({ variant: "outline" })}>
								Go to Hephaestus
							</Link>
						)}
				</CardFooter>
			</Card>
		</div>
	);
}
