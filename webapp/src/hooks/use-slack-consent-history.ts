import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { listSlackChannelConsentEventsOptions } from "@/api/@tanstack/react-query.gen";
import type { ChannelHistory } from "@/components/admin/integrations/slack-channels/ChannelHistorySheet";

/**
 * The consent history of the channel whose sheet is open. It is read only while the sheet is open:
 * the last read stays for the sheet's exit, and an open reads the history again.
 */
export function useSlackConsentHistory(workspaceSlug: string): {
	consentHistory: ChannelHistory;
	onHistoryOpenChange: (slackChannelId: string, open: boolean) => void;
} {
	const [shown, setShown] = useState<{ slackChannelId: string; open: boolean } | null>(null);
	const query = useQuery({
		...listSlackChannelConsentEventsOptions({
			path: { workspaceSlug, slackChannelId: shown?.slackChannelId ?? "" },
		}),
		enabled: shown?.open === true,
	});
	return {
		consentHistory: {
			events: query.data,
			isLoading: query.isLoading,
			error: query.isError ? query.error : undefined,
			onRetry: () => {
				void query.refetch();
			},
		},
		onHistoryOpenChange: (slackChannelId, open) => setShown({ slackChannelId, open }),
	};
}
