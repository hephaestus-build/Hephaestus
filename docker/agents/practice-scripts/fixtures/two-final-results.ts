import { definePracticeScript } from "../lib/core/practice-script.ts";

export default definePracticeScript({
	meta: {},
	async run() {
		const final = JSON.stringify({ kind: "result", value: {} });
		process.send?.(final);
		process.send?.(final);
		return Promise.withResolvers<never>().promise;
	},
});
