package chisel3.experimental

import chisel3.Data
import chisel3.experimental.dataview.reifyIdentityView
import chisel3.internal.{Builder, throwException}
import chisel3.reflect.DataMirror

object CheckBoring {
  def apply[A <: Data](_source: A):Boolean = {
    val (source, _) =
      reifyIdentityView(_source).getOrElse(
        throwException(s"BoringUtils currently only support identity views, ${_source} has multiple targets.")
      )
    val thisModule = Builder.currentModule.get
    val lcaResult = DataMirror.findLCAPaths(source, thisModule)
    lcaResult.isDefined
  }
}
