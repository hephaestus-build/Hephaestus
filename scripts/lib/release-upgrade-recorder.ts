/**
 * TEST ONLY. The provider APIs of the upgrade drill: it answers every call to the hosts in `PROVIDER_HOSTS` over TLS,
 * and lists the calls at `GET /calls?after=<sequence>` on its control port. It sends nothing on. The answers are empty
 * successes, so a release continues after its write and the drill sees the next one.
 *
 * Run: `node release-upgrade-recorder.ts <key.pem> <cert.pem>`. Provider calls on 443, the control port on 8080.
 */
import { spawnSync } from "node:child_process";
import { once } from "node:events";
import { chmodSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer as createHttpServer, IncomingMessage, type Server } from "node:http";
import { createServer as createHttpsServer, request as httpsRequest } from "node:https";
import nodePath from "node:path";
import process from "node:process";

import {
	PLANTED_SOURCE,
	PROVIDER_HOSTS,
	type ProviderCall,
	SOURCE_HEADER,
} from "./release-upgrade-writes.ts";

const MAX_BODY_BYTES = 1024 * 1024;

async function readBody(request: IncomingMessage): Promise<string> {
	const chunks: Buffer[] = [];
	let size = 0;
	for await (const part of request) {
		const chunk = Buffer.isBuffer(part) ? part : Buffer.from(String(part));
		size += chunk.length;
		if (size > MAX_BODY_BYTES) {
			throw new RangeError("the request body is too large");
		}
		chunks.push(chunk);
	}
	return Buffer.concat(chunks).toString("utf8");
}

/** The caller's address without the IPv4-mapped IPv6 prefix, as `docker inspect` names it. */
function addressOf(request: IncomingMessage): string {
	return (request.socket.remoteAddress ?? "").replace(/^::ffff:/u, "");
}

function answerOf(host: string, path: string): string {
	if (host === "slack.com" || host === "hooks.slack.com") {
		return JSON.stringify({ ok: true });
	}
	return path.split("?", 1)[0]?.endsWith("/graphql") === true ? JSON.stringify({ data: {} }) : "{}";
}

function openssl(directory: string, ...args: string[]): void {
	const result = spawnSync("openssl", args, { cwd: directory, encoding: "utf8" });
	if (result.status !== 0) {
		throw new Error(`openssl ${args[0] ?? ""} failed:\n${result.stdout}${result.stderr}`);
	}
}

/**
 * Writes a CA of one day and the recorder's certificate for every provider host into `directory`. A release trusts
 * `ca.crt` through `SSL_CERT_FILE`, as the chart's `config.privateCa` does. The CA's key is removed once it signed.
 */
export function writeProviderCertificates(directory: string): {
	ca: string;
	key: string;
	cert: string;
} {
	const path = (file: string) => nodePath.join(directory, file);
	openssl(
		directory,
		"req",
		"-x509",
		"-newkey",
		"rsa:2048",
		"-nodes",
		"-days",
		"1",
		"-subj",
		"/CN=Upgrade drill CA",
		"-keyout",
		"ca.key",
		"-out",
		"ca.crt",
	);
	openssl(
		directory,
		"req",
		"-newkey",
		"rsa:2048",
		"-nodes",
		"-subj",
		"/CN=upgrade-drill-providers",
		"-keyout",
		"key.pem",
		"-out",
		"cert.csr",
	);
	writeFileSync(
		path("cert.ext"),
		[
			"basicConstraints=CA:FALSE",
			"extendedKeyUsage=serverAuth",
			`subjectAltName=${PROVIDER_HOSTS.map((host) => `DNS:${host}`).join(",")}`,
			"",
		].join("\n"),
	);
	openssl(
		directory,
		"x509",
		"-req",
		"-days",
		"1",
		"-in",
		"cert.csr",
		"-CA",
		"ca.crt",
		"-CAkey",
		"ca.key",
		"-CAcreateserial",
		"-extfile",
		"cert.ext",
		"-out",
		"cert.pem",
	);
	for (const file of ["ca.key", "ca.srl", "cert.csr", "cert.ext"]) {
		rmSync(path(file), { force: true });
	}
	// Each release reads the CA, and the recorder its key and certificate, as a user of its own image.
	for (const file of ["ca.crt", "key.pem", "cert.pem"]) {
		chmodSync(path(file), 0o644);
	}
	chmodSync(directory, 0o755);
	return { ca: path("ca.crt"), key: path("key.pem"), cert: path("cert.pem") };
}

/**
 * Posts one write for `host` to the recorder at `127.0.0.1:port` under the planted label, as the drill's own proof
 * that a repeat is caught. Answers the status.
 */
export async function plantWrite(
	port: number,
	ca: string,
	host: string,
	path: string,
	body: string,
): Promise<number> {
	const sent = httpsRequest({
		host: "127.0.0.1",
		port,
		servername: host,
		ca,
		method: "POST",
		path,
		headers: { host, [SOURCE_HEADER]: PLANTED_SOURCE },
		timeout: 30_000,
	});
	sent.once("timeout", () => {
		sent.destroy(new Error("the recorder did not answer in time"));
	});
	sent.end(body);
	const answered: unknown[] = await once(sent, "response");
	const response = answered[0];
	if (!(response instanceof IncomingMessage)) {
		throw new TypeError("the recorder sent no response");
	}
	response.resume();
	await once(response, "end");
	return response.statusCode ?? 0;
}

export function providerRecorder(tls: { key: string; cert: string }): {
	providers: Server;
	control: Server;
} {
	const calls: ProviderCall[] = [];
	const providers = createHttpsServer(tls, (request, response) => {
		void (async () => {
			try {
				const body = await readBody(request);
				const host = (request.headers.host ?? "").replace(/:\d+$/u, "").toLowerCase();
				const path = request.url ?? "/";
				const label = request.headers[SOURCE_HEADER];
				calls.push({
					sequence: calls.length + 1,
					source: typeof label === "string" ? label : addressOf(request),
					method: request.method ?? "GET",
					host,
					path,
					body,
				});
				response.writeHead(200, { "content-type": "application/json" }).end(answerOf(host, path));
			} catch {
				response.writeHead(400).end();
			}
		})();
	});
	const control = createHttpServer((request, response) => {
		const url = new URL(request.url ?? "/", "http://recorder");
		if (request.method !== "GET" || url.pathname !== "/calls") {
			response.writeHead(404).end();
			return;
		}
		const after = Number(url.searchParams.get("after") ?? "0");
		response
			.writeHead(200, { "content-type": "application/json" })
			.end(JSON.stringify(calls.filter((call) => call.sequence > after)));
	});
	return { providers, control };
}

if (import.meta.main) {
	const [keyFile = "", certFile = ""] = process.argv.slice(2);
	const { providers, control } = providerRecorder({
		key: readFileSync(keyFile, "utf8"),
		cert: readFileSync(certFile, "utf8"),
	});
	providers.listen(443, "0.0.0.0");
	control.listen(8080, "0.0.0.0");
	for (const signal of ["SIGTERM", "SIGINT"] as const) {
		process.once(signal, () => {
			providers.close();
			control.close();
		});
	}
}
