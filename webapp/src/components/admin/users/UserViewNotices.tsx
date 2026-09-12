import { InfoIcon } from "lucide-react";

import type { PanelState } from "@/components/common/panel-state";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

export type UserViewNoticesState = PanelState<{
	practicesEnabled: boolean;
	mentorEnabled: boolean;
}>;

export interface UserViewNoticesProps {
	state: UserViewNoticesState;
}

export function UserViewNotices({ state }: UserViewNoticesProps) {
	if (state.status !== "ready") return null;
	if (state.practicesEnabled && state.mentorEnabled) return null;
	return (
		<Alert role="note">
			<InfoIcon aria-hidden />
			<AlertTitle>This view shows more than the user's own profile</AlertTitle>
			<AlertDescription>
				{!state.practicesEnabled && (
					<p>
						Practices are disabled in this workspace. The user's regular profile hides standings;
						this user view shows existing information.
					</p>
				)}
				{!state.mentorEnabled && (
					<p>
						Heph is disabled in this workspace. Only previously saved conversations can be viewed.
					</p>
				)}
			</AlertDescription>
		</Alert>
	);
}
