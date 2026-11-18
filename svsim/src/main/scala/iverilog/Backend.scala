// SPDX-License-Identifier: Apache-2.0
package svsim.iverilog

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption}
import svsim.{Backend => BackendApi, _}

object Backend {
  final case class CompilationSettings() extends BackendApi.Settings

  def initializeFromProcessEnvironment(): Backend = {
    val windows = File.separatorChar == '\\'
    def tool(name: String): String = Option(System.getenv("PATH")).getOrElse("").split(File.pathSeparator).iterator
      .map(dir => new File(dir, if (windows) s"$name.exe" else name))
      .find(_.canExecute).map(_.getAbsolutePath)
      .getOrElse(throw new BackendApi.Exceptions.FailedInitialization(s"Icarus Verilog: cannot find $name on PATH"))
    new Backend(tool("iverilog"), tool("iverilog-vpi"), tool("vvp"))
  }
}

final class Backend private (compiler: String, vpiCompiler: String, runtime: String) extends BackendApi {
  type CompilationSettings = Backend.CompilationSettings
  def escapeDefine(s: String): String = s
  def assertionFailed = "^FATAL:.*".r

  def generateParameters(
    outputBinaryName: String, topModuleName: String, additionalHeaderPaths: Seq[String],
    commonSettings: CommonCompilationSettings, backendSpecificSettings: CompilationSettings
  ): BackendApi.Parameters = {
    val s = commonSettings
    require(s.linkLibraryPaths.isEmpty, "Icarus does not support DPI link libraries")
    val flags = Seq("-g2012", "-s", topModuleName, "-o", outputBinaryName) ++
      s.defaultTimescale.toSeq.map(t => s"-ptimescale=$t") ++
      s.verilogPreprocessorDefines.map(d => "-D" + d.name + d.value.fold("")(v => "=" + v)) ++
      (s.includeDirs.toSeq.flatten ++ additionalHeaderPaths).flatMap(p => Seq("-I", p)) ++
      s.libraryPaths.toSeq.flatten.flatMap(p => Seq("-y", p)) ++
      s.libraryExtensions.toSeq.flatten.flatMap(e => Seq("-Y", e))
    import BackendApi.Parameters.Invocation
    val runtimeArgs = Seq("-vcd", s"+svsimTraceFileStem=${s.simulationSettings.traceFileStem}") ++
      s.simulationSettings.plusArgs.map(_.simulatorFlags)
    BackendApi.Parameters(compiler, Invocation(flags, Nil), Invocation(runtimeArgs, Nil))
  }

  private[svsim] def compile(dir: File, dut: ModuleInfo, params: BackendApi.Parameters, sources: Seq[String],
    cwd: Option[String], verbose: Boolean): Simulation = {
    val work = dir.toPath
    val log = work.resolve("compilation-log.txt").toFile
    Files.deleteIfExists(log.toPath)
    def run(args: Seq[String]): Unit = {
      val status = new ProcessBuilder(args: _*).directory(dir).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start().waitFor()
      if (status != 0) throw new Exception(s"${args.head} failed ($status):\n" + new String(Files.readAllBytes(log.toPath), UTF_8))
    }
    val driver = getClass.getResourceAsStream("/simulation-driver.cpp")
    try Files.copy(driver, work.resolve("simulation-driver.cpp"), StandardCopyOption.REPLACE_EXISTING)
    finally driver.close()

    def write(name: String, text: String): Unit = { Files.write(work.resolve(name), text.getBytes(UTF_8)); () }
    write("icarus.cpp", Templates.vpi(dut))
    write("testbench.sv", Templates.testbench(dut))
    require(!sources.exists(p => Seq(".c", ".cc", ".cpp").exists(p.endsWith)), "Icarus does not support DPI sources")
    val hdl = sources.filter(p => p.endsWith(".v") || p.endsWith(".sv"))
    val compileCommands = Seq(
      Seq(vpiCompiler, "icarus.cpp"),
      params.compilerPath +: (params.compilerInvocation.arguments ++ hdl :+ "testbench.sv")
    )
    val simulationLog = work.resolve("simulation-log.txt").toString
    val runtimeArgs = Seq("-n", "-q", "-l", simulationLog, "-M", dir.getAbsolutePath, "-m", "icarus",
      work.resolve("simulation").toString) ++ params.simulationInvocation.arguments
    val runtimeEnv = params.simulationInvocation.environment.toMap +
      ("SVSIM_SIMULATION_LOG" -> simulationLog)
    write("Makefile", Templates.makefile(work.toAbsolutePath, compileCommands, runtime +: runtimeArgs, runtimeEnv, cwd))
    compileCommands.foreach(run)
    if (verbose) print(new String(Files.readAllBytes(log.toPath), UTF_8))
    new Simulation(runtime, Simulation.Settings(cwd, runtimeArgs, runtimeEnv), dir.getAbsolutePath, dut)
  }
}
