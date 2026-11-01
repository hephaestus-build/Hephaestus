/**
 * Packs the files of `deploy/platform/` into the one signed bundle of the one-host install,
 * `hephaestus-k3s-<version>.tar.gz`.
 *
 * The same files always give the same bytes: sorted names, one fixed modification time, owner 0:0,
 * mode 0755 for `*.sh` and 0644 for the rest, and a gzip header with no name and no time. The
 * release workflow signs the archive, so a rebuild from the same commit gives the signed bytes.
 * The job has Node.js only, so this script uses no dependency.
 *
 * Usage: node scripts/package-k3s-bundle.ts <output.tar.gz> <mtime-in-epoch-seconds> [directory]
 */
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";

const ROOT = path.resolve(import.meta.dirname, "..");
const BLOCK = 512;
/** The gzip header byte for the file system of origin: 3 is Unix, whatever host builds the archive. */
const GZIP_OS_UNIX = 3;

export interface BundleMember {
	name: string;
	mode: number;
	contents: Buffer;
}

/** The members of a bundle: every file of the directory, by name, with its fixed mode. */
export function readMembers(directory: string): BundleMember[] {
	return readdirSync(directory, { withFileTypes: true })
		.toSorted((a, b) => Buffer.compare(Buffer.from(a.name), Buffer.from(b.name)))
		.map((entry) => {
			if (!entry.isFile()) {
				throw new Error(`${entry.name} is not a regular file; the bundle holds regular files only`);
			}
			return {
				name: entry.name,
				mode: entry.name.endsWith(".sh") ? 0o755 : 0o644,
				contents: readFileSync(path.join(directory, entry.name)),
			};
		});
}

/** A ustar header field: ASCII text, NUL-padded to its width. */
function field(block: Buffer, offset: number, width: number, value: string): void {
	if (!/^[\u0020-\u007E]*$/u.test(value) || value.length > width) {
		throw new Error(`"${value}" does not fit a ustar field of ${width} characters`);
	}
	block.write(value, offset, "ascii");
}

const octal = (value: number, width: number) => value.toString(8).padStart(width - 1, "0");

function header(member: BundleMember, mtime: number): Buffer {
	const block = Buffer.alloc(BLOCK);
	field(block, 0, 100, member.name);
	field(block, 100, 8, octal(member.mode, 8));
	field(block, 108, 8, octal(0, 8));
	field(block, 116, 8, octal(0, 8));
	field(block, 124, 12, octal(member.contents.length, 12));
	field(block, 136, 12, octal(mtime, 12));
	// The checksum counts its own field as eight spaces.
	block.fill(0x20, 148, 156);
	field(block, 156, 1, "0");
	field(block, 257, 6, "ustar");
	field(block, 263, 2, "00");
	const sum = block.reduce((total, byte) => total + byte, 0);
	// Six octal digits, a NUL and a space.
	field(block, 148, 6, octal(sum, 7));
	block[154] = 0;
	return block;
}

/** The ustar archive of the members, ended by two empty blocks. */
export function tar(members: readonly BundleMember[], mtime: number): Buffer {
	const parts = members.flatMap((member) => [
		header(member, mtime),
		member.contents,
		Buffer.alloc((BLOCK - (member.contents.length % BLOCK)) % BLOCK),
	]);
	return Buffer.concat([...parts, Buffer.alloc(2 * BLOCK)]);
}

/** The bundle of a directory: its files as one reproducible `.tar.gz`. */
export function packBundle(directory: string, mtime: number): Buffer {
	if (!Number.isSafeInteger(mtime) || mtime < 0) {
		throw new Error("the modification time must be a whole number of epoch seconds");
	}
	const archive = gzipSync(tar(readMembers(directory), mtime), { level: 9 });
	// zlib writes no name and a time of 0; only the file system byte depends on the host.
	archive[9] = GZIP_OS_UNIX;
	return archive;
}

if (import.meta.main) {
	const [output, time, directory = path.join(ROOT, "deploy/platform")] = process.argv.slice(2);
	if (output === undefined || time === undefined || !/^\d+$/u.test(time)) {
		process.stderr.write(
			"usage: node scripts/package-k3s-bundle.ts <output.tar.gz> <mtime-in-epoch-seconds> [directory]\n",
		);
		process.exit(2);
	}
	writeFileSync(output, packBundle(directory, Number(time)));
}
