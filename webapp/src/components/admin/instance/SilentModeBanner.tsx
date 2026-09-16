import { Link } from "@tanstack/react-router";
import { VolumeX } from "lucide-react";

import type { InstanceSettings } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import { asDate } from "@/lib/dates";
import { hasText } from "@/lib/text";

export interface SilentModeBannerProps {
	settings: InstanceSettings;
}

export function SilentModeBanner({ settings }: SilentModeBannerProps) {
	const engagedAt = asDate(settings.silentModeChangedAt);

	return (
		<Alert variant="destructive">
			<VolumeX aria-hidden />
			<AlertTitle className="min-w-0 break-words">
				Silent mode is engaged — workspace delivery is blocked
			</AlertTitle>
			<AlertDescription className="min-w-0 break-words">
				Practice feedback and workspace Slack messages are suppressed across this instance.
				{hasText(settings.silentModeChangedBy) || engagedAt ? (
					<>
						{" Engaged"}
						{hasText(settings.silentModeChangedBy) ? ` by ${settings.silentModeChangedBy}` : ""}
						{engagedAt ? (
							<>
								{" "}
								<RelativeTime value={settings.silentModeChangedAt} tooltip={false} />
							</>
						) : null}
						{hasText(settings.silentModeReason) ? ` — “${settings.silentModeReason}”` : ""}.
					</>
				) : null}
			</AlertDescription>
			<AlertAction>
				<Link to="/admin/settings" className={buttonVariants({ variant: "outline", size: "sm" })}>
					Manage
				</Link>
			</AlertAction>
		</Alert>
	);
}
