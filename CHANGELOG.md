# Changelog

## 0.2.2

- Increase interface and code font sizes, brighten secondary labels and respect desktop font smoothing.
- Render the compact guide preview at double resolution.

## 0.2.1

- Redesign the desktop around the repository sidebar and document inspector.
- Show thread, monitor wait, and cycle counts above the snapshot report.
- Put capture and notebook actions beside their content and show cancellation during active jobs.

## 0.2.0

- Reuse unchanged source files across searches, refreshes, and restarts.
- Inspect additions, edits, removals, and unambiguous exact-content renames.
- Select a search hit to highlight its source line. Changed files prompt a new search.
- Read live indexing progress and review operation outcomes in **Jobs & index**.
- Upgrade existing workspaces to schema 2 while preserving evidence and notes. A cancelled index transaction leaves the prior completed index intact.

## 0.1.1

- Shorter README with a monochrome cover; full instructions moved to the user guide.
- Consistent dark desktop tabs, buttons, lists, scrollbars, and focus indicators.
- Keep the desktop busy until an operation's UI updates finish.
- Wait for cancelled workers to exit before cleaning temporary response files.
- Reject incompatible worker protocol versions and malformed response payloads.
- Add cancellation and protocol compatibility integration checks.
- Separate test classes from the distributed application and add a checked packaging command.

## 0.1.0

- First investigation workflow: repository inspection, Git patches, JVM monitor analysis,
  persistent evidence and notes, and standalone HTML export.
