// A model call that the library never sends: its options have no JSON form. The script then waits
// for the runner to stop it.
import { definePrecompute } from "../lib/precompute.ts";

export default definePrecompute({
	meta: { models: { chat: "required" }, kinds: { unused: "Not used." } },
	async run() {
		process.send?.({
			kind: "model",
			id: -1,
			slot: "chat",
			label: "",
			options: { prompt: [{ role: "user", content: 1n }] },
		});
		return Promise.withResolvers<never>().promise;
	},
});
