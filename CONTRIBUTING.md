# Contributing

Start with a small issue describing a reproducible problem or a narrowly scoped improvement.
For larger features, discuss the design before implementing it; the current scope is in
`docs/roadmap.md`.

Run `./build.ps1` on Windows or `sh build.sh` on Linux/macOS. For UI changes, also run
`java -jar build/forge.jar --render docs/workbench.png` and inspect the result.

Keep repository inspection read-only, dynamic report content escaped, and slow work off
the Swing event-dispatch thread. Add a regression check when fixing incorrect behavior.
Use synthetic fixtures; do not submit personal logs, source snapshots, or credentials.

Describe the user-visible change and the checks you ran in your pull request.
