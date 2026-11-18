task("jar")
    set_menu {usage = "xmake jar", description = "Build the Chisel library, sources and POM under build/maven"}
    on_run(function ()
        local root = os.projectdir()
        local upstream = "7.16.0"
        local opts = {curdir = root, envs = {
            CHISEL_RELEASE_PLUGIN_VERSION = upstream,
            CHISEL_BUILD_VERSION = upstream .. "-redrisc.1"
        }}
        local args = {"--no-server", "unipublish[2.13].publishJar"}
        if is_host("windows") then
            os.execv("powershell", {"-NoProfile", "-Command", "mill", "--%", table.unpack(args)}, opts)
        else
            os.execv("mill", args, opts)
        end
    end)
task_end()
