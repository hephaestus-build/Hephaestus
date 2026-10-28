import { z } from "zod";

/** Sent links can select a thread and bring one of its messages into view. */
export const conversationSearchSchema = z.object({
	thread: z.uuid().optional().catch(undefined),
	entry: z.uuid().optional().catch(undefined),
});
