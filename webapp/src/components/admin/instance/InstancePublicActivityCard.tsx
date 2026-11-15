import { Globe } from "lucide-react";
import { useId } from "react";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

export type InstancePublicActivityState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			allowed: boolean;
			pending: boolean;
			/** Set when the latest read failed, so `allowed` is the last known setting. */
			refreshFailure?: RefreshFailure;
	  };

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
					<div className="flex flex-col gap-3">
						{state.refreshFailure !== undefined && (
							<QueryErrorAlert title="This setting may be out of date" {...state.refreshFailure} />
						)}
						<Field orientation="horizontal">
							<FieldContent>
								<FieldLabel htmlFor={id}>Allow public activity pages</FieldLabel>
								<FieldDescription>
									{state.allowed
										? "On. Workspaces can publish their page."
										: "Off. No workspace can publish a page."}
								</FieldDescription>
							</FieldContent>
							<Switch
								id={id}
								checked={state.allowed}
								onCheckedChange={(next) => onAllowedChange(next)}
								pending={state.pending}
							/>
						</Field>
					</div>
				)}
			</CardContent>
		</Card>
	);
}
