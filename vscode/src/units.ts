// The compilation units of a workspace, as `adm list --json` reports them.

export interface Unit {
	kind: "application" | "library" | "plugin";
	name: string;
	file: string;
	line: number;
	dir: string;
	manifest?: string;
	version?: string;
}

// The units in the output of `adm list <dir> --json`; none when the output
// is not that.
export function parseUnits(output: string): Unit[] {
	let parsed: unknown;
	try {
		parsed = JSON.parse(output);
	} catch {
		return [];
	}
	if (!Array.isArray(parsed)) {
		return [];
	}
	const units: Unit[] = [];
	for (const entry of parsed) {
		if (!entry || typeof entry !== "object") {
			continue;
		}
		const { kind, name, file, line, dir, manifest, version } = entry as Record<string, unknown>;
		if (typeof name !== "string" || typeof dir !== "string" || typeof file !== "string") {
			continue;
		}
		if (kind !== "application" && kind !== "library" && kind !== "plugin") {
			continue;
		}
		units.push({
			kind,
			name,
			file,
			dir,
			line: typeof line === "number" ? line : 1,
			manifest: typeof manifest === "string" ? manifest : undefined,
			version: typeof version === "string" ? version : undefined,
		});
	}
	return units;
}

// The unit a file belongs to: the one whose folder holds the file, the
// deepest when folders nest. `separator` is the path separator of the paths.
export function unitFor(units: Unit[], filePath: string, separator = "/"): Unit | undefined {
	let best: Unit | undefined;
	for (const unit of units) {
		const dir = unit.dir.endsWith(separator) ? unit.dir : unit.dir + separator;
		if (filePath !== unit.dir && !filePath.startsWith(dir)) {
			continue;
		}
		if (!best || unit.dir.length > best.dir.length) {
			best = unit;
		}
	}
	return best;
}
