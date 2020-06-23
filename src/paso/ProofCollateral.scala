// Copyright 2020 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>
package paso

import chisel3._
import paso.assertions._

import scala.collection.mutable

abstract class ProofCollateral[I <: RawModule, S <: UntimedModule](impl: I, spec: S)
  extends PasoAssertion with PasoForall with PasoMemCompare {
  val invs = new mutable.ArrayBuffer[I => Unit]()
  def invariances(gen: I => Unit): Unit = invs.append(gen)
  val maps = new mutable.ArrayBuffer[(I,S) => Unit]()
  def mapping(gen: (I, S) => Unit): Unit = maps.append(gen)
}
case class NoProofCollateral[I <: RawModule, S <: UntimedModule](impl: I, spec: S) extends ProofCollateral(impl, spec)