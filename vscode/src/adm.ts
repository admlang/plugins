// Finding and running the `adm` program.

import * as cp from "child_process";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";

// The `adm` to run: the configured one, looked up on PATH and then in
// ~/.adm/bin when it is a bare name. None when there is no such file.
export function admPath(): string | undefined {
	const configured = vscode.workspace.getConfiguration("adm").get<string>("path", "adm").trim() || "adm";
	if (configured.includes("/") || configured.includes("\\")) {
		const expanded = configured.startsWith("~") ? path.join(os.homedir(), configured.slice(1)) : configured;
		return fs.existsSync(expanded) ? expanded : undefined;
	}
	const suffixes = process.platform === "win32" ? [".exe", ".cmd", ""] : [""];
	const folders = (process.env.PATH ?? "").split(path.delimiter).filter((p) => p !== "");
	folders.push(path.join(os.homedir(), ".adm", "bin"));
	for (const folder of folders) {
		for (const suffix of suffixes) {
			const candidate = path.join(folder, configured + suffix);
			if (fs.existsSync(candidate)) {
				return candidate;
			}
		}
	}
	return undefined;
}

// The environment of every `adm` the extension starts.
export function admEnv(): NodeJS.ProcessEnv {
	const config = vscode.workspace.getConfiguration("adm");
	const env: NodeJS.ProcessEnv = { ...process.env };
	const lib = config.get<string>("lib", "").trim();
	if (lib !== "") {
		env.ADM_LIB = lib;
	}
	for (const [name, value] of Object.entries(config.get<Record<string, string>>("env", {}))) {
		env[name] = String(value);
	}
	return env;
}

// Tells the user that adm is missing, once per call, with the ways out.
export async function reportMissing(): Promise<void> {
	const install = "How to Install";
	const settings = "Open Settings";
	const picked = await vscode.window.showErrorMessage(
		"The adm program was not found on PATH or in ~/.adm/bin. Install ADM, or set adm.path.",
		install,
		settings,
	);
	if (picked === install) {
		await vscode.env.openExternal(vscode.Uri.parse("https://adm-lang.dev/#download"));
	} else if (picked === settings) {
		await vscode.commands.executeCommand("workbench.action.openSettings", "adm.path");
	}
}

export interface Ran {
	stdout: string;
	stderr: string;
	code: number;
}

// Runs adm to the end and returns what it printed.
export function runAdm(adm: string, args: string[], cwd: string): Promise<Ran> {
	return new Promise((resolve) => {
		cp.execFile(adm, args, { cwd, env: admEnv(), maxBuffer: 64 * 1024 * 1024 }, (err, stdout, stderr) => {
			const code = err && typeof (err as { code?: unknown }).code === "number" ? ((err as { code: number }).code) : err ? 1 : 0;
			resolve({ stdout, stderr, code });
		});
	});
}
