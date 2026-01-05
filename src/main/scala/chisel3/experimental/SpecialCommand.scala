package chisel3.experimental

import chisel3.internal.Builder
import chisel3.internal.Builder.pushCommand
import chisel3.internal.binding.WireBinding
import chisel3.internal.firrtl.ir.{Command, Connect, DefWire, ULit}
import chisel3.{Data, UInt, fromIntToWidth}

object SpecialWireInit {
  def apply(sourceInfo:SourceInfo, d:UInt, v:BigInt, prepend:Boolean):Unit = {
    val cmd = Connect(sourceInfo = sourceInfo, loc = d.ref, exp = ULit(v, d.getWidth.W))
    Builder.forcedUserModule.getBody.foreach(_.addPrependedCommands(cmd))
  }
}

object EscapedWire {
  def apply[T <: Data](source: => T)(implicit sourceInfo: SourceInfo): T = {
    val prevId = Builder.idGen.value
    val t = source // evaluate once (passed by name)
    requireIsChiselType(t, "wire type")

    val x = if (!t.mustClone(prevId)) t else t.cloneTypeFull

    // Bind each element of x to being a Wire
    x.bind(WireBinding(Builder.forcedUserModule, Builder.forcedUserModule.getBody))
    Builder.forcedUserModule.getBody.foreach(_.addPrependedCommands(DefWire(sourceInfo, x)))

    x
  }
}