import { z } from "zod";

const challenge = z.object({ code: z.literal("passkey_required") });
let navigation: Promise<void> | undefined;

export async function handlePasskeyChallenge(
	response: Response,
	navigate: () => Promise<void>,
): Promise<void> {
	if (response.status !== 403 || window.location.pathname === "/settings") {
		return;
	}
	const body: unknown = await response
		.clone()
		.json()
		.catch(() => undefined);
	if (challenge.safeParse(body).success) {
		navigation ??= navigate().finally(() => {
			navigation = undefined;
		});
		await navigation;
	}
}
