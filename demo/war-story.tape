# Records demo/war-story.gif. Reproduce from a clean clone:
#   mvn -q -DskipTests package && vhs demo/war-story.tape
# No API key, no network: it drives the real conductor daemon and the real
# PreToolUse enforcement hook against a scratch repo.
Output demo/war-story.gif

Set Shell "bash"
Set FontSize 18
Set Width 1180
Set Height 760
Set Padding 24
Set Theme "Catppuccin Mocha"

Type "# conductor: two sessions, one repo. The second is blocked before it can collide."
Enter
Sleep 1.5s
Type "CONDUCTOR_JAR=target/conductor.jar bash demo/war-story.sh"
Enter
Sleep 9s
