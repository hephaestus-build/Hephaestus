import { onlineManager } from "@tanstack/react-query";
import { RssIcon, WifiOffIcon } from "lucide-react";
import { useSyncExternalStore } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { useLivePushUnavailable } from "@/hooks/use-sync-liveness";

function useIsOnline(): boolean {
	return useSyncExternalStore(
		(onStoreChange) => onlineManager.subscribe(onStoreChange),
		() => onlineManager.isOnline(),
		() => true,
	);
}

export function SyncFreshnessBanner() {
	const isOnline = useIsOnline();
	const livePushUnavailable = useLivePushUnavailable();

	if (!isOnline) {
		return (
			<Alert variant="warning" role="status" aria-live="polite" className="mx-auto max-w-5xl">
				<WifiOffIcon />
				<AlertTitle>You’re offline — everything below is a snapshot</AlertTitle>
				<AlertDescription>
					Sync status stopped updating when the connection dropped. It will catch up on its own once
					you’re back.
				</AlertDescription>
			</Alert>
		);
	}

	if (livePushUnavailable) {
		return (
			<Alert role="status" aria-live="polite" className="mx-auto max-w-5xl">
				<RssIcon />
				<AlertTitle>Live updates are unavailable</AlertTitle>
				<AlertDescription>This section is refreshing periodically instead.</AlertDescription>
			</Alert>
		);
	}

	return null;
}
