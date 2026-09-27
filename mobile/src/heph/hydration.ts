/** What one conversation screen remembers between renders about sending and reading back. */
export interface Hydration {
	/** When this screen last sent a message; 0 before the first. */
	sentAt: number;
	/** When the newest server snapshot already considered was read; 0 before any. */
	consideredAt: number;
}

export function createHydration(): Hydration {
	return { sentAt: 0, consideredAt: 0 };
}

/**
 * The stored transcript to put on screen, or undefined to keep the local one.
 *
 * Each server snapshot is considered once. The chat hook copies its messages on every update, so a
 * rule that looked only at their content would apply the same snapshot again for each render it
 * causes; and a snapshot read while a reply streamed is out of date by the time the reply ends.
 *
 * A snapshot then replaces the local transcript only when no reply is streaming, it was read after
 * the last message this screen sent — cached data is never newer than the conversation in front of
 * the person — and it contains that message: a message that failed to send stays on screen so it can
 * be sent again.
 */
export function storedToApply<Message extends { id: string; role: string }>(
	hydration: Hydration,
	{
		busy,
		fetchedAt,
		stored,
		local,
	}: { busy: boolean; fetchedAt: number; stored: Message[] | undefined; local: readonly Message[] },
): Message[] | undefined {
	if (stored === undefined || fetchedAt <= hydration.consideredAt) {
		return undefined;
	}
	hydration.consideredAt = fetchedAt;
	if (busy || fetchedAt <= hydration.sentAt) {
		return undefined;
	}
	const lastSent =
		hydration.sentAt === 0 ? undefined : local.findLast((message) => message.role === "user");
	if (lastSent !== undefined && !stored.some((message) => message.id === lastSent.id)) {
		return undefined;
	}
	return stored;
}
