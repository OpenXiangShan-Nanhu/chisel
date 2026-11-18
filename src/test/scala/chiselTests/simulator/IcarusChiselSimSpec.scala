// SPDX-License-Identifier: Apache-2.0

package chiselTests.simulator

import chisel3._
import chisel3.simulator.{Exceptions, MacroText, Settings}
import chisel3.simulator.scalatest.{ChiselSim, Cli}
import chisel3.testing.HasTestingDirectory
import java.nio.file.Files
import org.scalatest.ConfigMap
import org.scalatest.funspec.AnyFunSpec

class IcarusChiselSimSpec extends AnyFunSpec with ChiselSim with Cli.Simulator {
  // Exercise the same config map entries that ScalaTest sets for -Dsimulator and -DemitVcd.
  override def configMap: ConfigMap =
    ConfigMap((super.configMap ++ Map("simulator" -> "iverilog", "emitVcd" -> "true")).toSeq: _*)

  private def withIcarus(body: => Unit): Unit =
    try body
    catch { case e: svsim.Backend.Exceptions.FailedInitialization => cancel(e.getMessage) }

  it("selects Icarus and emits a VCD through the CLI while simulating a Chisel register") {
    withIcarus {
      class Accumulator extends Module {
        val in = IO(Input(UInt(8.W)))
        val sum = IO(Output(UInt(8.W)))
        val acc = RegInit(0.U(8.W))
        acc := acc + in
        sum := acc + in
      }
      simulate(new Accumulator) { dut =>
        dut.in.poke(3.U)
        dut.sum.expect(3.U)
        dut.clock.step(2)
        dut.sum.expect(9.U)
      }
      val wave = implicitly[HasTestingDirectory].getDirectory.resolve("workdir-iverilog").resolve("trace.vcd")
      assert(Files.size(wave) > 0)
    }
  }

  it("honors the Chisel STOP_COND macro and reports an unmasked assertion failure") {
    withIcarus {
      class Checked extends Module {
        val fail = IO(Input(Bool()))
        val armed = IO(Input(Bool()))
        chisel3.assert(!fail, "Icarus assertion example")
      }
      val settings = Settings.default[Checked].copy(stopCond = Some(MacroText.Signal[Checked](_.armed)))
      var survivedMaskedAssertion = false
      intercept[Exceptions.AssertionFailed] {
        simulate(new Checked, settings = settings) { dut =>
          dut.armed.poke(false.B)
          dut.fail.poke(true.B)
          dut.clock.step()
          dut.fail.expect(true.B)
          survivedMaskedAssertion = true
          dut.armed.poke(true.B)
          dut.clock.step()
        }
      }
      assert(survivedMaskedAssertion, "the assertion stopped simulation before STOP_COND was enabled")
    }
  }
}
