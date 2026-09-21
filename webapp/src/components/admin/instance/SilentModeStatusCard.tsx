import { Link } from "@tanstack/react-router";
import { ArrowRight, Volume2, VolumeX } from "lucide-react";
import type { ReactNode } from "react";

import type { InstanceSettings } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import {
	Card,
	CardAction,
	CardContent,
	CardDescription,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

export interface SilentModeStatusCardProps {
	settings?: InstanceSettings;
	isLoading?: boolean;
	isError?: boolean;
}

export function SilentModeStatusCard({
	settings,
	isLoading = false,
	isError = false,
}: SilentModeStatusCardProps) {
	const engaged = settings?.silentModeEngaged === true;
	let body: ReactNode;
	if (isLoading) {
		body = <Skeleton className="h-6 w-40" />;
	} else if (isError) {
		body = (
			<div className="space-y-1">
				<Badge variant="outline">Unknown</Badge>
				<p className="text-sm text-muted-foreground">
					Couldn&rsquo;t read the delivery state — open instance settings to check.
				</p>
			</div>
		);
	} else if (engaged) {
		body = (
			<div className="space-y-1">
				<Badge variant="destructive">Silent mode engaged</Badge>
				<p className="text-sm text-muted-foreground">
					Workspace delivery is blocked
					{hasText(settings.silentModeChangedBy)
						? ` — engaged by ${settings.silentModeChangedBy}`
						: ""}
					{settings.silentModeChangedAt ? (
						<>
							{" "}
							<RelativeTime value={settings.silentModeChangedAt} tooltip={false} />
						</>
					) : null}
					.
				</p>
			</div>
		);
	} else {
		body = (
			<div className="space-y-1">
				<Badge variant="success">Delivering</Badge>
				<p className="text-sm text-muted-foreground">
					Practice feedback and Slack messages go out normally.
				</p>
			</div>
		);
	}

	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					{engaged ? (
						<VolumeX className="size-4 text-destructive" aria-hidden />
					) : (
						<Volume2 className="size-4 text-muted-foreground" aria-hidden />
					)}
					Delivery
				</CardTitle>
				<CardDescription>Outbound feedback and messages, instance-wide</CardDescription>
				<CardAction>
					<Link to="/admin/settings" className={buttonVariants({ variant: "ghost", size: "sm" })}>
						Manage
						<ArrowRight aria-hidden />
					</Link>
				</CardAction>
			</CardHeader>
			<CardContent>{body}</CardContent>
		</Card>
	);
}
