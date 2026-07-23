# Records docs/flock.gif. Reproduce from a clean clone (fathom built once):
#   (cd ../fathom && mvn -q -DskipTests package)
#   mvn -q -DskipTests package && FATHOM_JAR=../fathom/target/fathom.jar vhs demo/flock.tape
# No API key: real conductor daemon + FlockEngine, a live `fathom serve` MCP
# subprocess, and mcp-pact's real SchemaShape classifier. The two sessions are
# registered over the same bus API a real Claude Code session uses.
Output docs/flock.gif

Set Shell "bash"
Set FontSize 14
Set Width 1280
Set Height 720
Set Padding 22
Set Theme "Catppuccin Mocha"

Type "# Flock: a breaking change in service-a, caught in service-b — before it lands."
Enter
Sleep 1.5s
Type "FATHOM_JAR=../fathom/target/fathom.jar bash demo/flock.sh"
Enter
Sleep 12s
