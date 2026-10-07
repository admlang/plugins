// Test names as `adm test` prints them: `module::check("Suite")::Test`.

export interface TestName {
	module: string;
	suite: string;
	test: string;
}

export interface ListedTest {
	name: string;
	module: string;
	file: string;
	line: number;
}

const shape = /^(.*?)::check\("((?:[^"\\]|\\.)*)"\)::(.+)$/;

// The parts of a test name; none for a name of another shape.
export function parseTestName(name: string): TestName | undefined {
	const m = shape.exec(name);
	if (!m) {
		return undefined;
	}
	return { module: m[1], suite: m[2].replace(/\\(.)/g, "$1"), test: m[3] };
}

// The tests in the output of `adm test --list --json`.
export function parseTestList(output: string): ListedTest[] {
	let parsed: unknown;
	try {
		parsed = JSON.parse(output);
	} catch {
		return [];
	}
	if (!Array.isArray(parsed)) {
		return [];
	}
	const tests: ListedTest[] = [];
	for (const entry of parsed) {
		if (!entry || typeof entry !== "object") {
			continue;
		}
		const { name, module, file, line } = entry as Record<string, unknown>;
		if (typeof name !== "string" || typeof file !== "string") {
			continue;
		}
		tests.push({
			name,
			file,
			module: typeof module === "string" ? module : "",
			line: typeof line === "number" ? line : 1,
		});
	}
	return tests;
}

// The arguments that make `adm test` run exactly the named tests: nothing
// when they are all there is, else one exact `--run` each.
export function runFilter(selected: string[], all: number): string[] {
	if (selected.length >= all) {
		return [];
	}
	const args: string[] = [];
	for (const name of selected) {
		args.push("--run", "=" + name);
	}
	return args;
}
