import { CirclePause } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";

export interface SuspendedWorkspaceCardProps {
	workspaceName: string;
	resuming: boolean;
	/** Why the last resume failed, in the server's words. */
	resumeError?: string;
	onResume: () => void;
}

/**
 * The settings of a suspended workspace. A workspace admin and an instance admin both reach it, so it
 * names no scope.
 */
export function SuspendedWorkspaceCard({
	workspaceName,
	resuming,
	resumeError,
	onResume,
}: SuspendedWorkspaceCardProps) {
	return (
		<Card className="max-w-4xl">
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					<CirclePause className="size-5 text-muted-foreground" aria-hidden />
					{workspaceName} is suspended
				</CardTitle>
			</CardHeader>
			<CardContent className="space-y-2 text-sm leading-relaxed text-muted-foreground">
				<p>
					While it is suspended, it syncs nothing, runs no practice reviews, and sends no feedback.
					Its members cannot open it. Its data stays.
				</p>
				<p>
					If GitHub suspended the Hephaestus app for this workspace, unsuspend the app in GitHub
					instead. The workspace then resumes by itself. A resume here would not last.
				</p>
				{resumeError !== undefined && (
					<Alert variant="destructive">
						<AlertTitle>We could not resume the workspace</AlertTitle>
						<AlertDescription>{resumeError}</AlertDescription>
					</Alert>
				)}
			</CardContent>
			<CardFooter>
				<Button onClick={onResume} disabled={resuming}>
					{resuming && <Spinner aria-hidden />}
					{resuming ? "Resuming…" : "Resume workspace"}
				</Button>
			</CardFooter>
		</Card>
	);
}
