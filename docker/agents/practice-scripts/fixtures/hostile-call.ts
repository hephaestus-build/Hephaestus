import { definePracticeScript } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: { models: { chat: "required" } },
	async run() {
		process.send?.({
			kind: "model",
			id: 0,
			slot: "chat",
			label: "",
			options: { prompt: [{ role: "user", content: 1n }] },
		});
		return Promise.withResolvers<never>().promise;
	},
});
