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
} from "@/hooks/conversation-cache";
import { usePageVisible } from "@/hooks/use-page-visible";
import { parseLiveEvent } from "@/lib/conversation-entry";
import { openEventStream } from "@/lib/event-stream";

/**
 * Keeps the conversation live while `enabled` and the page is visible. A hidden tab closes its
 * stream, so tabs left open in the background do not each hold a connection. When the page shows
 * again, the stream opens again and its first event, a snapshot, reads back what changed meanwhile.
 * Returns whether the stream keeps failing and is reconnecting.
 */
export function useConversationLive(workspaceSlug: string, enabled: boolean): boolean {
	const queryClient = useQueryClient();
	const [paused, setPaused] = useState(false);
	const visible = usePageVisible();
	const streaming = enabled && visible;

	useEffect(() => {
		if (!streaming) {
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
	}, [streaming, workspaceSlug, queryClient]);

	return streaming && paused;
}
