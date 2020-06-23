// Copyright 2020, SiFive, Inc
// released under Apache License Version 2.0
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package assertions


import org.scalatest._

import chisel3._
import paso._


/**
 * Paso allows Invariances to be specified outside of the RTL implementation in the form of Assertions.
 * We can refer to the signals of the RTL implementation by using the AOP approach which allows us to
 * generate Firrtl in the context of a module that has already been elaborated.
 * However, we do not actually want the generated firrtl to end up as part of the implementation. On the other
 * hand though, we do need to ensure that the implementation signals (Wire, Reg, Mem, Node, ...)
 * that the assertion refers to are not eliminated in the compilation process. We thus want these signals
 * to be converted to outputs of the toplevel module using the Wiring transform.
 *
 * The following unit tests try to ensure that all of this is working as intended.
 */
class AssertionSpec extends FlatSpec {
  "Simple assertion" should "compile" in {
    def invariances(c: Counter): Unit = {
      // TODO: replace with paso.assert
      chisel3.assert(c.c <= 15.U)
    }


  }

  "Guarded assertion" should "compile" in {
    def invariances(c: Counter): Unit = {
      when(c.io.enabled) {
        // TODO: replace with paso.assert
        chisel3.assert(c.c <= 15.U)
      }
    }


  }
}


class Counter extends Module {
  val io = IO(new Bundle {
    val enabled = Input(Bool())
    val value = Output(UInt(4.W))
  })

  val c = RegInit(0.U(4.W))
  when(io.enabled) { c := c + 1.U }
  io.value := c
}
