import { definePracticeScript } from "@hephaestus/practice-scripts/models";

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
