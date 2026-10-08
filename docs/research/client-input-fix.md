# Character picker disappearing after login

> Historical evidence. For current behavior and workflow, read [the research index](README.md)
> and [AGENTS.md](../../AGENTS.md). Past client experiments are not current instructions.

The installed October 2026 client enumerates the laptop touchpads and `keyd
virtual pointer` as joysticks. Its GUI polls both mapped gamepads and raw
joysticks. GUI navigation binds controller button 1 to Escape unconditionally.
A raw pointer button event therefore dismisses the character picker, leaving
the UI state pointing to a removed window. Login remains connected; subsequent
logout logs a request to remove an unmanaged `cB` window.

A temporary client traced the removal through `t.pollGamepads`,
`ControllerEvent.dispatch`, `M.controllerPressed`, `M.keyPressed`, and
`aD.dismiss`. No character-selection RPC preceded that removal.

The tested client workaround changes one call in `t.pollGamepads` from
`GLFW.glfwJoystickPresent(int)` to `GLFW.glfwJoystickIsGamepad(int)`. This filters
unmapped devices before creating controller events. Keyboard, mouse and mapped
gamepads remain enabled; unmapped controllers require a proper GLFW mapping.
The server cannot control this client input polling.

An ignored research overlay was tested with the installed Java runtime,
deployment properties, and a temporary copy of the saved account/preferences.
The picker remained present for 28 seconds after login; GUI mouse dispatch then
selected the existing knight, received the explicit world-unavailable notice,
and opened creation through an empty slot. The test created no additional knight
and did not modify the user's profile. Client assets are not tracked here.

For a local installation, the patched class can live in the client's `config.jar`,
which precedes `projectx-pcode.jar` on its normal classpath. Back up the original
configuration archive before replacing it. This is a client workaround and is
not included in the standalone server JAR. Game updates can replace the patched
archive, so a lasting integration belongs in the separate launcher repository.
Installation requires separate authorization from the server-only work.
