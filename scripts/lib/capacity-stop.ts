import { asArray, asRecord, parseJson } from "./json.ts";

/** The caller owns these synthetic sessions and this isolated release's loopback endpoint. */
export async function stopSyntheticTurns(base: string, tokens: readonly string[]) {
	let stopped = 0;
	let failures = 0;
	let remaining = 0;
	const deadline = AbortSignal.timeout(90_000);
	async function stopAccount(token: string) {
		const headers = { authorization: `Bearer ${token}`, "content-type": "application/json" };
		try {
			const response = await fetch(`${base}/conversation`, {
				headers,
				signal: AbortSignal.any([deadline, AbortSignal.timeout(5000)]),
			});
			if (!response.ok) {
				await response.arrayBuffer();
				failures += 1;
				return;
			}
			const conversation = asRecord(parseJson(await response.text()), "conversation");
			for (const value of asArray(conversation.activeTurns, "active turns")) {
				const turn = asRecord(value, "turn");
				if (typeof turn.id !== "string" || !/^[a-f0-9-]{36}$/u.test(turn.id)) {
					throw new Error("Invalid synthetic turn ID");
				}
				const result = await fetch(`${base}/turns/${turn.id}/status`, {
					method: "PATCH",
					headers,
					body: JSON.stringify({ status: "STOPPED" }),
					signal: AbortSignal.any([deadline, AbortSignal.timeout(5000)]),
				});
				await result.arrayBuffer();
				if (result.ok) {
					stopped += 1;
				} else {
					failures += 1;
				}
			}
			const verified = await fetch(`${base}/conversation`, {
				headers,
				signal: AbortSignal.any([deadline, AbortSignal.timeout(5000)]),
			});
			if (!verified.ok) {
				await verified.arrayBuffer();
				failures += 1;
				return;
			}
			remaining += asArray(
				asRecord(parseJson(await verified.text()), "conversation").activeTurns,
				"active turns",
			).length;
		} catch {
			failures += 1;
		}
	}
	for (let offset = 0; offset < tokens.length; offset += 10) {
		await Promise.all(tokens.slice(offset, offset + 10).map(stopAccount));
	}
	return { stopped, failures, remaining };
}
