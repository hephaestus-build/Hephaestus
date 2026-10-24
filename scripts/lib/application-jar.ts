// The executable server JAR: the CI artifact when HEPHAESTUS_APPLICATION_JAR names it, a local build otherwise.
import { glob } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

import { isSet } from "./env.ts";
import { run } from "./process.ts";

export const serverDirectory = path.join(import.meta.dirname, "..", "..", "server");
const wrapper = path.join(import.meta.dirname, "..", "run-gradlew.ts");

export async function executableJar(gradleArguments: readonly string[] = []): Promise<string> {
	const configured = process.env.HEPHAESTUS_APPLICATION_JAR;
	if (isSet(configured)) {
		return configured;
	}
	await run(process.execPath, [wrapper, ":application:bootJar", ...gradleArguments], {
		cwd: serverDirectory,
	});
	const built = await Array.fromAsync(
		glob("application/build/libs/hephaestus-application-*.jar", { cwd: serverDirectory }),
	);
	const jars = built.filter((jar) => !/-(?:sources|javadoc)\.jar$/u.test(jar));
	if (jars.length !== 1) {
		throw new Error(`Expected one executable JAR, found ${jars.length}`);
	}
	return path.join(serverDirectory, jars[0] ?? "");
}
