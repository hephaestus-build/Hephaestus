import { Globe } from "lucide-react";
import { useId } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";

export type InstancePublicActivityState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; allowed: boolean; pending: boolean };

export interface InstancePublicActivityCardProps {
	state: InstancePublicActivityState;
	onAllowedChange: (allowed: boolean) => void;
}

/** Whether any workspace of the instance may publish its activity page. */
export function InstancePublicActivityCard({
	state,
	onAllowedChange,
}: InstancePublicActivityCardProps) {
	const id = useId();
	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					<Globe className="size-4 text-muted-foreground" aria-hidden />
					Public activity pages
				</CardTitle>
				<CardDescription>
					A public activity page shows who contributes to a workspace’s public repositories, with no
					sign-in. Each workspace turns its own page on. Turn this off to stop every public page at
					once.
				</CardDescription>
			</CardHeader>
			<CardContent>
				{state.status === "loading" && <Skeleton className="h-10 rounded-lg" />}
				{state.status === "error" && (
					<QueryErrorAlert
						error={state.error}
						title="We could not load the public activity setting"
						onRetry={state.onRetry}
					/>
				)}
				{state.status === "ready" && (
					<Field orientation="horizontal">
						<FieldContent>
							<FieldLabel htmlFor={id}>Allow public activity pages</FieldLabel>
							<FieldDescription>
								{state.allowed
									? "On. Workspaces can publish their page."
									: "Off. No workspace can publish a page."}
							</FieldDescription>
						</FieldContent>
						{state.pending && <Spinner />}
						<Switch
							id={id}
							checked={state.allowed}
							onCheckedChange={(next) => onAllowedChange(next)}
							readOnly={state.pending}
						/>
					</Field>
				)}
			</CardContent>
		</Card>
	);
}
