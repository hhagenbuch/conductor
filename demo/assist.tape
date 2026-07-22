# Records docs/assist.gif. Reproduce from a clean clone:
#   mvn -q -DskipTests package && vhs demo/assist.tape
# No API key, no network: real conductor orchestration (daemon, registry,
# leases, worktree, briefing, inbox); only the helper's leaf process is
# scripted in place of a real `claude -p` (via CONDUCTOR_HELPER_CMD).
Output docs/assist.gif

Set Shell "bash"
Set FontSize 15
Set Width 1240
Set Height 1000
Set Padding 22
Set Theme "Catppuccin Mocha"

Type "# conductor assist: one command hands a lease-disjoint slice to a briefed helper."
Enter
Sleep 1.5s
Type "CONDUCTOR_JAR=target/conductor.jar bash demo/assist.sh"
Enter
Sleep 16s
