/**
 * Gradle only adds dependency-verification entries and cannot report an unused one. An entry for a
 * version that no build downloads any more stays, and OSV-Scanner reports it as a dependency. This
 * resolves the server's dependencies in an empty Gradle user home with strict verification, then
 * fails on each component that it did not download. `--write` deletes those components instead.
 * Regenerating the file with `--write-verification-metadata` would also work, but it rewrites the
 * `origin` of every entry and reports no component by name.
 *
 * The empty home still fetches nothing from the network that the caller's own Gradle cache holds
 * with a checksum the metadata trusts: a loopback endpoint answers Maven Central and Plugin Portal
 * requests with those files and forwards the rest. Gradle recommends a caching proxy in front of
 * Maven Central, which rate limits a host that downloads a whole graph
 * (https://blog.gradle.org/maven-central-mirror). The endpoint serves only bytes that strict
 * verification accepts, so the set of components the resolution reads stays the same.
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, readdir, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { homedir, tmpdir } from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const metadataPath = path.join(root, "server", "gradle", "verification-metadata.xml");

// Resolution alone downloads every component: compiling reads no more. Spotless resolves its
// formatters only when its tasks run. A component that only another task reads shows up as unused.
const tasks = [
	"resolveDependencies",
	"dependencies",
	"buildEnvironment",
	":spotlessCheck",
	":application:spotlessCheck",
];

// gradle/actions injects this plugin into the dependency-graph workflows, so no server build downloads
// it. Only its newest version stays: an older one is from a gradle/actions release no workflow uses.
const injected = new Set(["org.gradle:github-dependency-graph-gradle-plugin"]);

// Plain dotted numbers only, which is what the plugin releases use.
function compareVersions(left: string, right: string): number {
	const a = left.split(".").map(Number);
	const b = right.split(".").map(Number);
	for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
		const difference = (a[index] ?? 0) - (b[index] ?? 0);
		if (difference !== 0) {
			return difference;
		}
	}
	return 0;
}

// Gradle writes one component per block at this indentation.
const componentPattern =
	/^ {6}<component group="(?<group>[^"]+)" name="(?<name>[^"]+)" version="(?<version>[^"]+)">\n[\s\S]*?^ {6}<\/component>\n/gmu;

function components(xml: string) {
	const matches = [...xml.matchAll(componentPattern)];
	if (matches.length !== xml.split("<component ").length - 1) {
		throw new Error("verification-metadata.xml is not in the layout Gradle writes");
	}
	return matches.map((match) => {
		const { group, name, version } = match.groups ?? {};
		assert.ok(group !== undefined && name !== undefined && version !== undefined);
		return {
			module: `${group}:${name}`,
			version,
			id: `${group}:${name}:${version}`,
			block: match[0],
		};
	});
}

export function staleComponents(xml: string, downloaded: ReadonlySet<string>): string[] {
	const all = components(xml);
	const newest = new Map<string, string>();
	for (const { module, version } of all) {
		const kept = newest.get(module);
		if (injected.has(module) && (kept === undefined || compareVersions(version, kept) > 0)) {
			newest.set(module, version);
		}
	}
	return all
		.filter(({ module, version, id }) => !downloaded.has(id) && newest.get(module) !== version)
		.map(({ id }) => id);
}

export function withoutComponents(xml: string, stale: ReadonlySet<string>): string {
	let pruned = xml;
	for (const { id, block } of components(xml)) {
		if (stale.has(id)) {
			pruned = pruned.replace(block, "");
		}
	}
	return pruned;
}

async function downloadedComponents(gradleHome: string): Promise<Set<string>> {
	const files = path.join(gradleHome, "caches", "modules-2", "files-2.1");
	const ids = new Set<string>();
	for (const group of await readdir(files)) {
		for (const name of await readdir(path.join(files, group))) {
			for (const version of await readdir(path.join(files, group, name))) {
				ids.add(`${group}:${name}:${version}`);
			}
		}
	}
	return ids;
}

// The build's two remote repositories, by the path prefix that the loopback endpoint serves them under.
const upstreams = {
	central: "https://repo.maven.apache.org/maven2",
	portal: "https://plugins.gradle.org/m2",
};

/** The SHA-256 checksums the metadata trusts, by `group:name:version/file`. */
export function trustedChecksums(xml: string): Map<string, Set<string>> {
	const trusted = new Map<string, Set<string>>();
	for (const { id, block } of components(xml)) {
		for (const artifact of block.matchAll(
			/<artifact name="(?<file>[^"]+)">(?<body>[\s\S]*?)<\/artifact>/gu,
		)) {
			const { file, body = "" } = artifact.groups ?? {};
			const checksums = [...body.matchAll(/<sha256 value="(?<value>[0-9a-f]+)"/gu)].flatMap(
				({ groups }) => (groups?.value === undefined ? [] : [groups.value]),
			);
			trusted.set(`${id}/${String(file)}`, new Set(checksums));
		}
	}
	return trusted;
}

/**
 * The content of the file in a Gradle cache's `files-2.1` that answers a Maven repository path, which
 * ends in `group/as/path/name/version/file`. Only a file with a checksum that `trusted` lists answers:
 * two repositories can publish different files under one name, and the cache keeps both. The bytes
 * returned are the bytes hashed, so a cache that changes meanwhile cannot slip in others.
 */
export async function cachedFile(
	files: string,
	repositoryPath: string,
	trusted: ReadonlyMap<string, ReadonlySet<string>>,
): Promise<Buffer | undefined> {
	const segments = repositoryPath.split("/");
	if (
		segments.length < 4 ||
		segments.some((segment) => segment === "" || segment.startsWith("."))
	) {
		return undefined;
	}
	const [name, version, file] = segments.slice(-3);
	assert.ok(name !== undefined && version !== undefined && file !== undefined);
	const group = segments.slice(0, -3).join(".");
	const checksums = trusted.get(`${group}:${name}:${version}/${file}`);
	if (checksums === undefined) {
		return undefined;
	}
	const directory = path.join(files, group, name, version);
	try {
		for (const entry of await readdir(directory)) {
			const candidate = path.join(directory, entry, file);
			const content = await readFile(candidate).catch(() => undefined);
			if (
				content !== undefined &&
				checksums.has(createHash("sha256").update(content).digest("hex"))
			) {
				return content;
			}
		}
	} catch (error) {
		if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) {
			throw error;
		}
	}
	return undefined;
}

/** Serves `/<upstream>/<path>` from `files`, or from that upstream when the cache lacks it. */
export async function serveRestoredCache(
	files: string,
	trusted: ReadonlyMap<string, ReadonlySet<string>>,
	remotes: Readonly<Record<string, string>> = upstreams,
) {
	const served = { cache: 0, upstream: 0 };
	async function answer(request: IncomingMessage, response: ServerResponse): Promise<void> {
		const [, prefix = "", ...rest] = new URL(request.url ?? "/", "http://loopback").pathname.split(
			"/",
		);
		const remote = remotes[prefix];
		const repositoryPath = rest.join("/");
		try {
			if (remote === undefined) {
				response.writeHead(404).end();
				return;
			}
			// Gradle asks for a file's SHA-1 before it downloads a file that another repository already
			// fetched; the SHA-1 of the trusted cached file answers that request exactly.
			const checksum = repositoryPath.endsWith(".sha1");
			const local = await cachedFile(files, repositoryPath.replace(/\.sha1$/u, ""), trusted);
			if (local === undefined) {
				served.upstream += 1;
				const upstream = await fetch(`${remote}/${repositoryPath}`, { method: request.method });
				response.writeHead(upstream.status).end(Buffer.from(await upstream.arrayBuffer()));
			} else {
				served.cache += 1;
				response.writeHead(200);
				if (request.method === "HEAD") {
					response.end();
				} else if (checksum) {
					response.end(createHash("sha1").update(local).digest("hex"));
				} else {
					response.end(local);
				}
			}
		} catch (error) {
			console.error(`check-gradle-verification-metadata: ${String(error)}`);
			response.destroy();
		}
	}
	const server = createServer((request, response) => {
		void answer(request, response);
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(typeof address === "object" && address !== null);
	return {
		url: `http://127.0.0.1:${String(address.port)}`,
		served,
		close: async () => {
			server.close();
			await once(server, "close");
		},
	};
}

// Points the build's Maven Central and Plugin Portal repositories, as `upstreams` names them, at the
// loopback endpoint. Gradle adds neither before `beforeSettings`, so `configureEach` sees both.
const initScript = String.raw`
def endpoint = System.getenv("HEPHAESTUS_VERIFICATION_ENDPOINT")
def throughEndpoint = { repositories ->
  repositories.withType(MavenArtifactRepository).configureEach { repository ->
    def url = repository.url.toString()
    if (url.startsWith("https://repo.maven.apache.org/maven2")) {
      repository.url = "${"$"}{endpoint}/central"
      repository.allowInsecureProtocol = true
    } else if (url.startsWith("https://plugins.gradle.org/m2")) {
      repository.url = "${"$"}{endpoint}/portal"
      repository.allowInsecureProtocol = true
    }
  }
}
beforeSettings { settings ->
  throughEndpoint(settings.pluginManagement.repositories)
  throughEndpoint(settings.buildscript.repositories)
}
settingsEvaluated { settings -> throughEndpoint(settings.dependencyResolutionManagement.repositories) }
`;

async function main(): Promise<void> {
	const write = process.argv.includes("--write");
	const callerHome = process.env.GRADLE_USER_HOME ?? path.join(homedir(), ".gradle");
	const endpoint = await serveRestoredCache(
		path.join(callerHome, "caches", "modules-2", "files-2.1"),
		trustedChecksums(await readFile(metadataPath, "utf8")),
	);
	try {
		await resolveInEmptyHome(callerHome, endpoint, write);
	} finally {
		await endpoint.close();
	}
}

async function resolveInEmptyHome(
	callerHome: string,
	endpoint: Awaited<ReturnType<typeof serveRestoredCache>>,
	write: boolean,
): Promise<void> {
	const gradleHome = await mkdtemp(path.join(tmpdir(), "gradle-home-"));
	try {
		const init = path.join(gradleHome, "through-endpoint.init.gradle");
		await writeFile(init, initScript);
		// The wrapper's distribution is no dependency, so the empty home may reuse the caller's.
		const wrapper = path.join(callerHome, "wrapper");
		if (
			await stat(wrapper).then(
				(entry) => entry.isDirectory(),
				() => false,
			)
		) {
			// A junction links a directory on Windows without the symlink privilege; POSIX ignores it.
			await symlink(wrapper, path.join(gradleHome, "wrapper"), "junction");
		}
		// A warm cache skips parent POMs and module descriptors, so only an empty home shows every read.
		// The endpoint answers in this process, so Gradle runs asynchronously.
		const build = spawn(
			process.execPath,
			[
				path.resolve(import.meta.dirname, "run-gradlew.ts"),
				"--no-daemon",
				"--no-configuration-cache",
				"--dependency-verification=strict",
				"--console=plain",
				"--init-script",
				init,
				...tasks,
			],
			{
				cwd: root,
				stdio: "inherit",
				env: {
					...process.env,
					GRADLE_USER_HOME: gradleHome,
					HEPHAESTUS_VERIFICATION_ENDPOINT: endpoint.url,
				},
			},
		);
		const closed: unknown[] = await once(build, "close");
		const [status, signal] = closed;
		console.log(
			`check-gradle-verification-metadata: ${String(endpoint.served.cache)} files from the local Gradle cache, ${String(endpoint.served.upstream)} from the network.`,
		);
		if (status !== 0) {
			throw new Error(
				`Gradle failed with ${typeof signal === "string" ? signal : `exit code ${String(status)}`}`,
			);
		}
		const xml = await readFile(metadataPath, "utf8");
		const stale = staleComponents(xml, await downloadedComponents(gradleHome));
		if (stale.length === 0) {
			console.log(
				"check-gradle-verification-metadata: a clean resolution downloads every component.",
			);
		} else if (write) {
			await writeFile(metadataPath, withoutComponents(xml, new Set(stale)));
			console.log(
				`check-gradle-verification-metadata: removed ${String(stale.length)} components.`,
			);
		} else {
			console.error(
				`A clean resolution does not download these components:\n${stale.map((id) => `  ${id}`).join("\n")}`,
			);
			console.error(
				"Run `node scripts/check-gradle-verification-metadata.ts --write` and commit the result. " +
					"If a CI task needs one of them, add that task to this script instead.",
			);
			process.exitCode = 1;
		}
	} finally {
		// Gradle's single-use daemon can still write to the home while it exits.
		await rm(gradleHome, { recursive: true, force: true, maxRetries: 10, retryDelay: 500 });
	}
}

if (import.meta.main) {
	await main();
}
