import { z } from "zod";

/**
 * A notification's data, as the server sends it: which workspace has new practice feedback, and for
 * which sign-in. It is validated before it steers navigation, because anything that can post to this
 * device's push token can put anything here; and a notification delivered before the person signed
 * out or into another account stays on the device, addressed to a sign-in that is no longer this one.
 */
const practiceFeedback = z.object({
	kind: z.literal("practice-feedback"),
	workspaceSlug: z.string().regex(/^[a-z0-9][a-z0-9-]{2,50}$/u),
	nativeSessionId: z.uuid(),
});

export type PushPayload = z.infer<typeof practiceFeedback>;

export function parsePushPayload(data: unknown): PushPayload | undefined {
	const parsed = practiceFeedback.safeParse(data);
	return parsed.success ? parsed.data : undefined;
}
