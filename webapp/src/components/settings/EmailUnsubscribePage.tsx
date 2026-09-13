import { useSpinDelay } from "spin-delay";

import { Button } from "@/components/ui/button";
import {
	Card,
	CardContent,
	CardDescription,
	CardFooter,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";

export type EmailUnsubscribeState =
	| { status: "confirm" | "pending" | "error"; onConfirm: () => void }
	| { status: "complete" | "invalid" };

export function EmailUnsubscribePage({ state }: { state: EmailUnsubscribeState }) {
	const showSpinner = useSpinDelay(state.status === "pending", { delay: 1000, minDuration: 500 });
	const busy = state.status === "pending" || showSpinner;
	const complete = state.status === "complete" && !showSpinner;

	return (
		<div className="mx-auto flex min-h-svh w-full max-w-lg items-center p-4">
			<Card className="w-full">
				<CardHeader>
					<CardTitle>
						<h1>{complete ? "Unsubscribe request processed" : "Unsubscribe from these emails?"}</h1>
					</CardTitle>
					<CardDescription aria-live="polite" aria-atomic="true">
						{complete
							? "If this link was active, its optional email subscription is now off. Other optional subscriptions and essential account emails are unchanged."
							: "Confirm to stop the optional email subscription associated with this link. This does not change other optional subscriptions or essential account emails."}
					</CardDescription>
				</CardHeader>
				{state.status === "invalid" && (
					<CardContent>
						<p>This link is incomplete. Open the unsubscribe link from your email again.</p>
					</CardContent>
				)}
				{state.status === "error" && !showSpinner && (
					<CardContent>
						<p role="alert" className="text-sm text-destructive">
							We couldn't confirm the result. You can safely try again.
						</p>
					</CardContent>
				)}
				{("onConfirm" in state || showSpinner) && (
					<CardFooter>
						<Button
							disabled={busy}
							onClick={() => {
								if (!busy && "onConfirm" in state) state.onConfirm();
							}}
						>
							{showSpinner && <Spinner />}
							{showSpinner ? "Unsubscribing…" : "Unsubscribe"}
						</Button>
					</CardFooter>
				)}
			</Card>
		</div>
	);
}
