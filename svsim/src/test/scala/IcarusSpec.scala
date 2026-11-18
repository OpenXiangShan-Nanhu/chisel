// SPDX-License-Identifier: Apache-2.0

package svsimTests

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funspec.AnyFunSpec
import scala.io.Source
import scala.jdk.CollectionConverters._
import svsim._
import svsimTests.Resources.TestWorkspace

class IcarusSpec extends AnyFunSpec {
  private def newWorkspace(name: String): Workspace = {
    val workspace = new Workspace(s"test_run_dir/IcarusSpec/$name")
    workspace.reset()
    workspace
  }

  private def compile(
    workspace: Workspace,
    settings:  CommonSimulationSettings,
    cwd:       Option[String] = None
  ): Simulation = {
    val backend = try iverilog.Backend.initializeFromProcessEnvironment()
    catch { case e: Backend.Exceptions.FailedInitialization => cancel(e.getMessage) }
    workspace.compile(backend)(
      "iverilog",
      CommonCompilationSettings(simulationSettings = settings),
      iverilog.Backend.CompilationSettings(),
      cwd,
      false
    )
  }

  private def compileGcd(
    name:     String,
    settings: CommonSimulationSettings = CommonSimulationSettings.default
  ): Simulation = {
    val workspace = newWorkspace(name)
    workspace.elaborateGCD()
    compile(workspace, settings)
  }

  it("runs a clocked DUT with signed ports, sentinel ticks and logs without opening a VCD") {
    val sim = compileGcd("GCD with spaces")
    sim.run() { c =>
      c.setTraceEnabled(false)
      val a = c.port("a")
      val clk = c.port("clock")
      a.set(-24)
      assert(a.get(isSigned = true).asBigInt == -24)
      c.port("b").set(36)
      c.port("loadValues").set(1)
      clk.tick(1, 1, 0, 1)
      c.port("loadValues").set(0)
      val elapsed = clk.tick(1, 10, 0, 1, Some((c.port("isValid"), BigInt(1))))
      assert(elapsed > 0 && elapsed < 10)
      assert(c.port("result").get().asBigInt == 12)
      assert(c.readLog().contains("Calculated GCD"))
    }
    assert(!Files.exists(sim.workingDirectory.resolve("trace.vcd")))
  }

  it("writes a custom VCD at time zero even when the simulation immediately exits") {
    val settings = CommonSimulationSettings.default.copy(
      enableWavesAtTimeZero = true,
      traceFileStem = "custom waveform"
    )
    val sim = compileGcd("time zero", settings)
    sim.run(traceEnabled = settings.enableWavesAtTimeZero)(_ => ())

    val lines = Files.readAllLines(sim.workingDirectory.resolve("custom waveform.vcd")).asScala
    assert(lines.contains("$enddefinitions $end"))
    assert(lines.find(_.startsWith("#")).contains("#0"))
    assert(!Files.exists(sim.workingDirectory.resolve("trace.vcd")))
  }

  it("starts tracing late, skips signal changes while off, and resumes in the same VCD") {
    val sim = compileGcd("dynamic")
    sim.run() { c =>
      val a = c.port("a")
      c.run(3)
      c.setTraceEnabled(true)
      c.run(1)
      a.set(1)
      c.run(1)
      c.setTraceEnabled(false)
      c.run(2)
      a.set(2)
      c.run(2)
      c.setTraceEnabled(true)
      c.run(2)
      a.set(3)
      c.run(1)
    }

    val lines = Files.readAllLines(sim.workingDirectory.resolve("trace.vcd")).asScala
    assert(lines.find(_.startsWith("#")).contains("#3"))
    assert(lines.contains("#4"))
    assert(lines.contains("$dumpoff"))
    assert(!lines.contains("#7"))
    assert(lines.contains("$dumpon"))
    assert(lines.contains("#11"))
  }

  private def write(path: Path, text: String): Path = Files.write(path, text.getBytes(UTF_8))
  private def read(path: Path): String = new String(Files.readAllBytes(path), UTF_8)

  private def replay(work: Path, transcript: Path): (Int, String) = {
    val builder = new ProcessBuilder("make", "replay").directory(work.toFile).redirectErrorStream(true)
    // An inherited setting must not truncate the script while replay is reading it.
    builder.environment().put("SVSIM_EXECUTION_SCRIPT", transcript.toString)
    builder.environment().put("SVSIM_EXECUTION_SCRIPT_LIMIT", "0")
    val process = builder.start()
    val output = Source.fromInputStream(process.getInputStream, "UTF-8")
    val text = try output.mkString
    finally output.close()
    (process.waitFor(), text)
  }

  it("rebuilds VPI and RTL and replays only commands using the original arguments, cwd and environment") {
    val workspace = newWorkspace("replay with spaces")
    val runtimeDir = Files.createDirectory(Paths.get(workspace.absolutePath, "runtime cwd"))
    write(runtimeDir.resolve("input with spaces.txt"), "input\n")
    val rtl = Paths.get(workspace.primarySourcesPath, "replay dut.sv")
    def design(value: Int): String =
      s"""module ReplayDut(output reg [7:0] value);
         |  string fileName;
         |  integer fd;
         |  initial begin
         |    if (!$$value$$plusargs("config=%s", fileName) || fileName != "input with spaces.txt")
         |      $$fatal(1, "bad runtime arguments");
         |    fd = $$fopen(fileName, "r");
         |    if (!fd) $$fatal(1, "bad runtime working directory");
         |    $$fclose(fd);
         |    value = $value;
         |    $$display("DUT value: %0d\\n123> !not-a-command", value);
         |  end
         |endmodule
         |""".stripMargin
    write(rtl, design(7))
    workspace.elaborate(ModuleInfo("ReplayDut", Seq(ModuleInfo.Port("value", isGettable = true))))
    val settings = CommonSimulationSettings.default.copy(
      plusArgs = Seq(new PlusArg("config", Some("input with spaces.txt")))
    )
    val simulation = compile(workspace, settings, Some("../runtime cwd"))
    simulation.run() { controller =>
      controller.run(1)
      assert(controller.readLog().contains("\n123> !not-a-command"))
      assert(controller.port("value").get().asBigInt == 7)
    }

    val work = simulation.workingDirectory
    val transcript = work.resolve("execution-script.txt")
    val original = Files.readAllBytes(transcript).toSeq
    val plugin = work.resolve("icarus.vpi")
    val compiled = work.resolve("simulation")
    val log = work.resolve("simulation-log.txt")
    def checkReplay(): (Int, String) = {
      val result = replay(work, transcript)
      assert(Files.readAllBytes(transcript).toSeq == original)
      result
    }

    write(rtl, design(19))
    Files.delete(plugin)
    Files.delete(compiled)
    Files.delete(log)
    val (status, output) = checkReplay()
    assert(status == 0, output)
    assert(Files.isRegularFile(plugin) && Files.isRegularFile(compiled))
    assert(output.contains("b 00000008 13"), output)
    assert(Files.isRegularFile(log))
    assert((output + read(log)).contains("DUT value: 19"))

    write(rtl, "module ReplayDut(\n")
    val (compileStatus, compileOutput) = checkReplay()
    assert(compileStatus != 0 && compileOutput.contains("syntax error"), compileOutput)
    assert(!compileOutput.contains("r ready"), compileOutput)

    write(rtl, """module ReplayDut(output value); initial $fatal(1, "replay failed"); endmodule""")
    val (runtimeStatus, runtimeOutput) = checkReplay()
    assert(runtimeStatus != 0, runtimeOutput)
    assert((runtimeOutput + read(log)).contains("replay failed"))
  }
}
