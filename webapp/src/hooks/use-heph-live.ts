import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import environment from "@/environment";
import {
	applyEntry,
	applyRead,
	applySnapshot,
	applyTurn,
	inOrder,
	rereadConversation,
	rereadInOrder,
} from "@/hooks/heph-cache";
import { openEventStream } from "@/lib/event-stream";
import { parseLiveEvent } from "@/lib/mentor-entry";

export function useHephLive(workspaceSlug: string, enabled: boolean): boolean {
	const queryClient = useQueryClient();
	const [paused, setPaused] = useState(false);

	useEffect(() => {
		if (!enabled) {
			return;
		}
		const handle = (event: MessageEvent<string>) => {
			const live = parseLiveEvent(event.type, event.data);
			switch (live?.type) {
				case "snapshot": {
					rereadInOrder(queryClient, workspaceSlug, async () =>
						applySnapshot(queryClient, workspaceSlug, live.conversation),
					);
					break;
				}
				case "turn": {
					inOrder(queryClient, workspaceSlug, () =>
						applyTurn(queryClient, workspaceSlug, live.turn),
					);
					break;
				}
				case "read": {
					// Another device, or this one, moved the read marker.
					const { lastReadAt } = live.read;
					if (lastReadAt !== undefined) {
						inOrder(queryClient, workspaceSlug, () =>
							applyRead(queryClient, workspaceSlug, lastReadAt),
						);
					}
					break;
				}
				case "entry": {
					inOrder(queryClient, workspaceSlug, () =>
						applyEntry(queryClient, workspaceSlug, live.entry),
					);
					break;
				}
				case undefined: {
					// The event cannot be read, so whatever it changed is read again from the server.
					rereadInOrder(queryClient, workspaceSlug, async () =>
						rereadConversation(queryClient, workspaceSlug),
					);
					break;
				}
			}
		};
		const close = openEventStream(
			`${environment.serverUrl}/workspaces/${workspaceSlug}/mentor/events`,
			{
				events: { snapshot: handle, turn: handle, entry: handle, read: handle },
				onPausedChange: setPaused,
			},
		);
		return () => {
			close();
			setPaused(false);
		};
	}, [enabled, workspaceSlug, queryClient]);

	return enabled && paused;
}
