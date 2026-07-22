# Records docs/war-story.gif. Reproduce from a clean clone:
#   mvn -q -DskipTests package && vhs demo/war-story.tape
# No API key, no network: it drives the real conductor daemon and the real
# PreToolUse enforcement hook against a scratch repo.
Output docs/war-story.gif

Set Shell "bash"
Set FontSize 16
Set Width 1200
Set Height 900
Set Padding 24
Set Theme "Catppuccin Mocha"

Type "# conductor: two sessions, one repo. The second is blocked before it can collide."
Enter
Sleep 1.5s
Type "CONDUCTOR_JAR=target/conductor.jar bash demo/war-story.sh"
Enter
Sleep 10s
