import { Button } from "@/components/ui/button";
import {
	Card,
	CardContent,
	CardDescription,
	CardFooter,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";

export type EmailUnsubscribeState =
	| { status: "confirm" | "pending" | "error"; onConfirm: () => void }
	| { status: "complete" | "invalid" };

export function EmailUnsubscribePage({ state }: { state: EmailUnsubscribeState }) {
	const pending = state.status === "pending";
	const complete = state.status === "complete";

	return (
		<div className="mx-auto flex min-h-svh w-full max-w-lg items-center p-4">
			<Card className="w-full">
				<CardHeader>
					<CardTitle>
						<h1>{complete ? "Unsubscribe request processed" : "Unsubscribe from these emails?"}</h1>
					</CardTitle>
					<CardDescription aria-live="polite" aria-atomic="true">
						{complete
							? "If this link was active, its optional email subscription is now off. Your other optional subscriptions and essential account emails stay as they are."
							: "Confirm to stop the optional emails that this link is for. Your other optional subscriptions and essential account emails stay as they are."}
					</CardDescription>
				</CardHeader>
				{state.status === "invalid" && (
					<CardContent>
						<p>This link is incomplete. Open the unsubscribe link from your email again.</p>
					</CardContent>
				)}
				{state.status === "error" && (
					<CardContent>
						<p role="alert" className="text-sm text-destructive">
							We could not confirm the result. You can safely try again.
						</p>
					</CardContent>
				)}
				{"onConfirm" in state && (
					<CardFooter>
						<Button pending={pending} onClick={state.onConfirm}>
							{pending ? "Unsubscribing…" : "Unsubscribe"}
						</Button>
					</CardFooter>
				)}
			</Card>
		</div>
	);
}
