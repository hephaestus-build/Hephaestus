import { definePracticeScript } from "@hephaestus/practice-scripts";

export default definePracticeScript({
	meta: {},
	async run() {
		let graph: unknown = {};
		for (let depth = 0; depth < 30; depth += 1) {
			graph = { a: graph, b: graph };
		}
		process.send?.({ kind: "log", text: graph });
		return Promise.withResolvers<never>().promise;
	},
});
