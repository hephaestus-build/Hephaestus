import { TriangleAlertIcon } from "lucide-react";

import { Button } from "~/components/common/Button";
import { Notice } from "~/components/common/Notice";
import { serverTooOldText } from "~/shared/server-version";

export interface ServerTooOldNoticeProps {
	instanceHost: string;
	serverVersion?: string;
	oldestServer: string;
	checking: boolean;
	onCheckAgain: () => void;
}

/** Stands in for sign-in and account while the extension does not support the instance. */
export function ServerTooOldNotice({ checking, onCheckAgain, ...facts }: ServerTooOldNoticeProps) {
	return (
		<Notice
			icon={TriangleAlertIcon}
			tone="warning"
			title="This server is too old for this extension"
			action={
				<Button variant="outline" size="sm" disabled={checking} onClick={onCheckAgain}>
					{checking ? "Checking…" : "Check again"}
				</Button>
			}
		>
			{serverTooOldText(facts)}
		</Notice>
	);
}
