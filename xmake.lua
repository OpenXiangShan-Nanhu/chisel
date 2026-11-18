task("jar")
    set_menu {usage = "xmake jar", description = "Build the Chisel library and source JARs"}
    on_run(function ()
        local root = os.projectdir()
        local opts = {curdir = root, envs = {CHISEL_RELEASE_PLUGIN_VERSION = "7.9.0"}}
        local args = {"--no-server", "unipublish[2.13].{jar,sourceJar}"}
        if is_host("windows") then
            -- Pass the Mill selector, including its braces and comma, literally.
            os.execv("powershell", {"-NoProfile", "-Command", "mill", "--%", table.unpack(args)}, opts)
        else
            os.execv("mill", args, opts)
        end

        local packaged = path.join(root, os.getenv("MILL_OUTPUT_DIR") or "out", "unipublish", "2.13")
        local build = path.join(root, "build")
        os.mkdir(build)
        os.cp(path.join(packaged, "jar.dest", "out.jar"), path.join(build, "chisel.jar"))
        os.cp(path.join(packaged, "sourceJar.dest", "out.jar"), path.join(build, "chisel-source.jar"))
    end)
task_end()
