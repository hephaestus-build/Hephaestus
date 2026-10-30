import { z } from "zod";

export const userViewSearchSchema = z.object({
	page: z.number().int().nonnegative().optional().catch(undefined),
	/** The account to view. A provider login is never an account id. */
	account: z
		.string()
		.regex(/^[1-9][0-9]*$/u)
		.optional()
		.catch(undefined),
});
export type UserViewSearch = z.infer<typeof userViewSearchSchema>;
