// Copyright 2020, SiFive, Inc
// released under Apache License Version 2.0
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package paso.chisel

import chisel3.RawModule
import chisel3.stage._
import firrtl.ir
import firrtl.stage.FirrtlCircuitAnnotation

object ChiselCompiler {
  private val stage = new chisel3.stage.ChiselStage
  def toLowFirrtl[M <: RawModule](gen: () => M): ir.Circuit = {
    val annos = Seq()
    val r = stage.execute(Array("-X", "low"), ChiselGeneratorAnnotation(gen) +: annos)
    // retrieve circuit
    r.collectFirst { case FirrtlCircuitAnnotation(a) => a }.get
  }
}
