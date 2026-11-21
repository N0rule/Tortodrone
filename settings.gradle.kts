rootProject.name = "tortodrone"

// The root project is the packaging/composition :app module. Source packages live in
// the six extracted projects below; split packages are assigned by extracted type.
val modules = listOf("core", "content", "protocol", "world", "gameplay", "server")
include(modules)
modules.forEach { project(":$it").projectDir = file("modules/$it") }
