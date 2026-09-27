import { z } from "zod";

/** Contains no account, workspace or review data: the page only learns whether to remove its UI. */
export const providerAccessSchema = z.object({
	type: z.literal("hephaestus:provider-access"),
	enabled: z.boolean(),
});
