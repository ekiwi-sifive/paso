// Copyright 2020, SiFive, Inc
// released under Apache License Version 2.0
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package paso.chisel

import chisel3.RawModule
import chisel3.stage._
import firrtl.annotations.DeletedAnnotation
import firrtl.options.Dependency
import firrtl.{EmitCircuitAnnotation, LowFirrtlCompiler, LowFirrtlEmitter, UnknownForm, ir}
import firrtl.stage.{CompilerAnnotation, FirrtlCircuitAnnotation, RunFirrtlTransformAnnotation}

object ChiselCompiler {
  private val stage = new chisel3.stage.ChiselStage {
    override val targets = Seq(
      Dependency[chisel3.stage.phases.Checks],
      Dependency[chisel3.stage.phases.Elaborate],
      Dependency[chisel3.stage.phases.MaybeAspectPhase],
      Dependency[chisel3.stage.phases.Convert],
      Dependency[chisel3.stage.phases.MaybeFirrtlStage])
  }
  def toLowFirrtl[M <: RawModule](gen: () => M): firrtl.CircuitState = {
    val genAnno = ChiselGeneratorAnnotation(gen)
    val lowFirrtlAnno = CompilerAnnotation(new LowFirrtlCompiler)
    val baseAnnos = Seq(genAnno, lowFirrtlAnno)
    val r = stage.run(baseAnnos)

    // retrieve circuit
    val circuit = r.collectFirst { case FirrtlCircuitAnnotation(a) => a }.get
    // filter out circuit and deleted annotations
    val finalAnnos = r.filter {
      case FirrtlCircuitAnnotation(_) | DeletedAnnotation(_, _) => false
      case _ => true
    }
    // build state
    firrtl.CircuitState(circuit, UnknownForm, finalAnnos)
  }
}