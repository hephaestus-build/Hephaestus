import { asRecord, asString, parseJson } from "./json.ts";
import { output } from "./process.ts";

/** Where `head` stands relative to `base` on GitHub: identical, ahead, behind or diverged. */
export async function compareStatus(
	repository: string,
	base: string,
	head: string,
): Promise<string> {
	return asString(
		asRecord(
			parseJson(await output("gh", ["api", `repos/${repository}/compare/${base}...${head}`])),
			"comparison",
		).status,
		"comparison status",
	);
}

/** A REST `POST` with a JSON body; `gh` supplies the token and the API host. */
export async function githubPost(path: string, body: object): Promise<unknown> {
	return parseJson(
		await output("gh", ["api", "--method", "POST", path, "--input", "-"], {
			input: JSON.stringify(body),
		}),
	);
}
