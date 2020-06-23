// Copyright 2020 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>
package paso.assertions

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util.log2Ceil
import firrtl.annotations.{ReferenceTarget, SingleTargetAnnotation}

/**
 * Paso Assertion
 * TODO: replace with native Firrtl assertion once Firrtl 1.4 is released
 */
trait PasoAssertion {
  // replace default chisel assert
  def assert(cond: => Bool): Unit = {
    val w = Wire(Bool()).suggestName("assert")
    w := cond
    annotate(new ChiselAnnotation { override def toFirrtl = AssertAnnotation(w.toTarget) })
  }
}

case class AssertAnnotation(target: ReferenceTarget) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(n)
}


/**
 * Makes it possible to compare whole memories instead of individual elements.
 */
trait PasoMemCompare {
  implicit class comparableMem[T <: UInt](x: Mem[T]) {
    def ===(y: Mem[T]): Bool = {
      require(x.length > 0)
      require(x.length == y.length)
      val w = Wire(Bool()).suggestName(s"eq($x, $y)")
      dontTouch(w)
      val depth = x.length
      val width = x.t.getWidth
      annotate(new ChiselAnnotation { override def toFirrtl = MemEqualAnnotation(w.toTarget, x.toTarget, y.toTarget, depth, width) })
      w
    }
  }
}

case class MemToVecAnnotation(target: ReferenceTarget, mem: ReferenceTarget, depth: BigInt, width: Int) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(n)
}

case class MemEqualAnnotation(target: ReferenceTarget, mem0: ReferenceTarget, mem1: ReferenceTarget, depth: BigInt, width: Int) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(n)
}

/**
 * The loop-like forall construct can be translated to a forall expression for model checking backends
 * that support quantifiers. If the backend does not support quantifiers, the forall will be unrolled.
 */
trait PasoForall {
  def forall(range: Range)(block: UInt => Unit): Unit = {
    require(range.step == 1, s"Only step size 1 supported: $range")
    require(range.start >= 0 && range.end >= 0, s"Only positive ranges supported: $range")
    require(range.start <= range.end)

    // generate a wire to represent the universally quantified variable
    val bits = log2Ceil(range.end)
    val ii = Wire(UInt(bits.W)).suggestName(s"ii_${range.start}_to_${range.end-1}")
    dontTouch(ii)
    annotate(new ChiselAnnotation { override def toFirrtl = ForallStartAnnotation(ii.toTarget, range.start, range.end) })

    // generate the block code once
    block(ii)

    // mark the end of the block (important: this only works if we do not run too many passes / optimizations)
    val end = WireInit(false.B).suggestName("forall_end")
    dontTouch(end)
    annotate(new ChiselAnnotation { override def toFirrtl = ForallEndAnnotation(end.toTarget) })
  }
}

case class ForallStartAnnotation(target: ReferenceTarget, start: Int, end: Int) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(n)
}

case class ForallEndAnnotation(target: ReferenceTarget) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(n)
}
