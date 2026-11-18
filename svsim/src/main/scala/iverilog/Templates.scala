// SPDX-License-Identifier: Apache-2.0

package svsim.iverilog

import java.io.File
import java.nio.file.Path
import svsim.{ModuleInfo, Workspace}

private[iverilog] object Templates {
  def testbench(dut: ModuleInfo): String =
    s"""// SPDX-License-Identifier: Apache-2.0
       |module ${Workspace.testbenchModuleName};
       |  ${dut.name} ${dut.instanceName}();
       |
       |  // Open the VCD only when tracing is first enabled.
       |  bit svsimTraceEnabled;
       |  bit svsimTraceStarted;
       |
       |  always @(svsimTraceEnabled) begin : svsimTrace
       |    string stem;
       |    if (svsimTraceEnabled) begin
       |      if (!svsimTraceStarted) begin
       |        if (!$$value$$plusargs("svsimTraceFileStem=%s", stem))
       |          stem = "trace";
       |        $$dumpfile({stem, ".vcd"});
       |        $$dumpvars(0, ${dut.instanceName});
       |        svsimTraceStarted = 1;
       |      end else begin
       |        $$dumpon;
       |      end
       |    end else if (svsimTraceStarted) begin
       |      $$dumpoff;
       |    end
       |  end
       |endmodule
       |""".stripMargin

  def vpi(dut: ModuleInfo): String = {
    val portNames = (dut.ports.map(p => s"\"${Workspace.testbenchModuleName}.${dut.instanceName}.${p.name}\"") :+ "nullptr").mkString(", ")
    val inputInitializers = dut.ports.zipWithIndex.collect { case (p, id) if p.isSettable => s"  initInput($id);" }.mkString("\n")
    s"""// SPDX-License-Identifier: Apache-2.0
       |// The shared command loop is synchronous, whereas VPI advances time by
       |// returning to the simulator. Suspend just the command stack at each run;
       |// all VPI operations remain on vvp's simulator thread.
       |#ifdef _WIN32
       |#include <windows.h>
       |#else
       |#include <ucontext.h>
       |#endif
       |#include <vector>
       |#include <vpi_user.h>
       |#define SVSIM_ENABLE_ICARUS_SUPPORT
       |#include "simulation-driver.cpp"
       |
       |static std::vector<vpiHandle> ports;
       |static vpiHandle readPort, writePort;
       |static void initInput(unsigned id);
       |static const char *names[] = {$portNames};
       |static void initInputs() {
       |$inputInitializers
       |}
       |
       |static void getBits(uint8_t *out) {
       |  s_vpi_value value{};
       |  value.format = vpiVectorVal;
       |  vpi_get_value(readPort, &value);
       |  for (int i = 0; i < (vpi_get(vpiSize, readPort) + 31) / 32; ++i) {
       |    auto word = value.value.vector[i];
       |    uint32_t bits = word.aval & ~word.bval; // match the two-state DPI API
       |    memcpy(out + i * 4, &bits, 4);
       |  }
       |}
       |
       |static void setBits(const uint8_t *in) {
       |  std::vector<s_vpi_vecval> words((vpi_get(vpiSize, writePort) + 31) / 32);
       |  for (size_t i = 0; i < words.size(); ++i)
       |    memcpy(&words[i].aval, in + i * 4, 4);
       |  s_vpi_value value{};
       |  value.format = vpiVectorVal;
       |  value.value.vector = words.data();
       |  vpi_put_value(writePort, &value, nullptr, vpiForceFlag);
       |}
       |
       |static void initInput(unsigned id) {
       |  writePort = ports.at(id);
       |  std::vector<uint8_t> zero(((vpi_get(vpiSize, writePort) + 31) / 32) * 4);
       |  setBits(zero.data());
       |}
       |
       |extern "C" int port_getter(int id, int *width, void (**get)(uint8_t *)) {
       |  if (static_cast<size_t>(id) >= ports.size()) return 1;
       |  readPort = ports[id];
       |  *width = vpi_get(vpiSize, readPort);
       |  *get = getBits;
       |  return 0;
       |}
       |extern "C" int port_setter(int id, int *width, void (**set)(const uint8_t *)) {
       |  if (static_cast<size_t>(id) >= ports.size()) return 1;
       |  writePort = ports[id];
       |  *width = vpi_get(vpiSize, writePort);
       |  *set = setBits;
       |  return 0;
       |}
       |
       |static vpiHandle traceControl;
       |
       |#ifdef _WIN32
       |static void *scheduler, *driver;
       |static void yield() { SwitchToFiber(scheduler); }
       |static void resume() { SwitchToFiber(driver); }
       |static VOID WINAPI drive(void *) {
       |#else
       |static ucontext_t scheduler, driver;
       |static char driverStack[256 * 1024];
       |static void yield() { swapcontext(&driver, &scheduler); }
       |static void resume() { swapcontext(&scheduler, &driver); }
       |static void drive() {
       |#endif
       |  simulation_body();
       |  vpi_control(vpiFinish, 0);
       |  yield();
       |}
       |
       |static void schedule(int reason, PLI_INT32 (*callback)(p_cb_data), unsigned delay = 0) {
       |  s_vpi_time time{};
       |  time.type = vpiSimTime;
       |  time.low = delay;
       |  s_cb_data cb{};
       |  cb.reason = reason;
       |  cb.cb_rtn = callback;
       |  cb.time = &time;
       |  vpi_free_object(vpi_register_cb(&cb));
       |}
       |
       |static PLI_INT32 onSettled(p_cb_data) { resume(); return 0; }
       |
       |extern "C" void simulation_initializeTrace(const char *) {
       |  // The HDL harness reads the stem from +svsimTraceFileStem.
       |}
       |
       |static void setTrace(bool enabled, int *ok) {
       |  *ok = traceControl != nullptr;
       |  if (!*ok) return;
       |
       |  s_vpi_value value{};
       |  value.format = vpiIntVal;
       |  value.value.integer = enabled;
       |  vpi_put_value(traceControl, &value, nullptr, vpiNoDelay);
       |
       |  // Run the HDL dump tasks before acknowledging, even if the next command
       |  // toggles tracing again without advancing time.
       |  schedule(cbReadWriteSynch, onSettled);
       |  yield();
       |}
       |
       |extern "C" void simulation_enableTrace(int *ok) { setTrace(true, ok); }
       |extern "C" void simulation_disableTrace(int *ok) { setTrace(false, ok); }
       |
       |extern "C" void run_simulation(int delay, int *finish) {
       |  if (delay < 0) failWithError("Negative simulation delay");
       |  schedule(cbReadWriteSynch, onSettled, delay);
       |  yield();
       |  *finish = 0;
       |}
       |
       |static PLI_INT32 start(p_cb_data) {
       |  initializeDriver();
       |  traceControl = vpi_handle_by_name("${Workspace.testbenchModuleName}.svsimTraceEnabled", nullptr);
       |  for (const char **name = names; *name; ++name) {
       |    auto handle = vpi_handle_by_name(*name, nullptr);
       |    if (!handle) failWithError("Icarus cannot resolve port %s", *name);
       |    ports.push_back(handle);
       |  }
       |  initInputs();
       |#ifdef _WIN32
       |  scheduler = IsThreadAFiber() ? GetCurrentFiber() : ConvertThreadToFiber(nullptr);
       |  driver = CreateFiber(0, drive, nullptr);
       |  if (!scheduler || !driver) failWithError("Cannot initialize Icarus command fiber");
       |#else
       |  getcontext(&driver);
       |  driver.uc_stack.ss_sp = driverStack;
       |  driver.uc_stack.ss_size = sizeof(driverStack);
       |  driver.uc_link = &scheduler;
       |  makecontext(&driver, drive, 0);
       |#endif
       |  schedule(cbReadWriteSynch, onSettled);
       |  return 0;
       |}
       |
       |static PLI_INT32 finish(p_cb_data) { simulation_final(); return 0; }
       |static void install() {
       |  schedule(cbStartOfSimulation, start);
       |  schedule(cbEndOfSimulation, finish);
       |}
       |extern "C" {
       |void (*vlog_startup_routines[])() = {install, nullptr};
       |}
       |""".stripMargin
  }

  def makefile(
    work:            Path,
    compileCommands: Seq[Seq[String]],
    runtimeCommand:  Seq[String],
    runtimeEnv:      Map[String, String],
    cwd:             Option[String]
  ): String = {
    def quote(arg: String): String = "'" + arg.replace("$", "$$").replace("'", "'\\''") + "'"
    def command(args: Seq[String]): String = args.map(quote).mkString(" ")
    def shellPath(path: Path): String = quote(path.toString.replace(File.separatorChar, '/'))

    val rebuild = compileCommands.map(args => s"\texec ${command(args)}").mkString("\n")
    val cd = cwd.fold("")(p => s"cd ${shellPath(work.resolve(p))} && ")
    val env = runtimeEnv.iterator.map { case (name, value) => s"$name=${quote(value)} " }.mkString
    val input = shellPath(work.resolve("replay-commands.txt"))
    val windowsTranscript = if (File.separatorChar == '\\') 1 else 0

    // Keep the awk program literal: Make, the shell and awk interpret its dollar signs in turn.
    s"windowsTranscript := $windowsTranscript\n" +
      """SHELL := sh
        |.DEFAULT_GOAL := replay
        |.PHONY: simulation replay
        |.INTERMEDIATE: replay-commands.txt
        |.DELETE_ON_ERROR:
        |unexport SVSIM_EXECUTION_SCRIPT SVSIM_EXECUTION_SCRIPT_LIMIT
        |
        |# LOG replies are byte-counted and can contain lines that look like commands.
        |# Read in the C locale; undo the driver's Windows text-mode LF expansion once.
        |replay-commands.txt: execution-script.txt
        |	@LC_ALL=C awk -v BINMODE=3 -v windows=$(windowsTranscript) ' \
        |		function hex(s,    i,n) { for (i=1; i<=length(s); i++) n=n*16+index("0123456789abcdef",tolower(substr(s,i,1)))-1; return n } \
        |		windows { sub(/\r$$/, "") } \
        |		remaining { remaining -= length($$0)+1; if (remaining < 0) exit 1; next } \
        |		match($$0, /^[0-9]+< l [[:xdigit:]]{8} /) { \
        |			remaining = hex(substr($$0,RLENGTH-8,8)) - (length($$0)-RLENGTH); if (remaining < 0) exit 1; next } \
        |		sub(/^[0-9]+> /, "") { print } \
        |		END { if (remaining != 0) exit 1 }' "$<" > "$@"
        |""".stripMargin +
      s"""
         |simulation:
         |$rebuild
         |
         |replay: simulation replay-commands.txt
         |	$cd${env}exec ${command(runtimeCommand)} < $input
         |""".stripMargin
  }
}
