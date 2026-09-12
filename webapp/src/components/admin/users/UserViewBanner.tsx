import { EyeIcon } from "lucide-react";
import { useId } from "react";

import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

export interface UserViewBannerProps {
	name: string;
	workspace: string;
	hasAccount: boolean;
	onExit: () => void;
}

/**
 * A region named by its own title rather than the primitive's live `alert` role: the banner mounts
 * with its text already inside, so a live region would announce nothing, while a landmark is where
 * a reader looks for whose data this is.
 */
export function UserViewBanner({ name, workspace, hasAccount, onExit }: UserViewBannerProps) {
	const titleId = useId();
	return (
		<Alert role="region" aria-labelledby={titleId} className="sticky top-0 z-20">
			<EyeIcon aria-hidden />
			<AlertTitle id={titleId} className="min-w-0 break-words">
				Viewing {name} in {workspace} — read-only
			</AlertTitle>
			<AlertDescription className="min-w-0 break-words">
				You remain signed in as yourself. This access is audited. Account setup and personal choices
				are not changed.
				{!hasAccount &&
					" No linked Hephaestus account. Existing practice information is still available."}
			</AlertDescription>
			<AlertAction>
				<Button type="button" variant="outline" size="sm" onClick={onExit}>
					Exit user view
				</Button>
			</AlertAction>
		</Alert>
	);
}
