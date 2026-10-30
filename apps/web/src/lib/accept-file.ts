/** Whether a dropped file matches an `accept` list of extensions, MIME types and `type/*` wildcards. */
export function acceptsFile(file: { name: string; type: string }, accept: string): boolean {
	const rules = accept
		.split(',')
		.map((r) => r.trim().toLowerCase())
		.filter(Boolean);
	if (rules.length === 0) return true;
	const name = file.name.toLowerCase();
	const type = file.type.toLowerCase();
	return rules.some((rule) =>
		rule.startsWith('.')
			? name.endsWith(rule)
			: rule.endsWith('/*')
				? type.startsWith(rule.slice(0, -1))
				: type === rule
	);
}
