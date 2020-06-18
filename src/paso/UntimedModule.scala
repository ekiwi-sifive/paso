// Copyright 2020 The Regents of the University of California
// Copyright 2020, SiFive, Inc
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package paso

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import firrtl.annotations.{ModuleTarget, SingleTargetAnnotation}
import paso.chisel.ChiselCompiler
import paso.untimed._

import scala.collection.mutable

class UntimedModule extends MultiIOModule with MethodParent {
  override private[paso] def addMethod(m: Method): Unit = _methods.append(m)
  override private[paso] def markDontCare(d: Data): Unit = _dontCareSignals.append(d)
  private val _dontCareSignals = mutable.ArrayBuffer[Data]()
  def getName: String = this.pathName
  override def isElaborated: Boolean =_isElaborated
  private var _isElaborated = false
  private var _firrtl: Option[firrtl.CircuitState] = None
  private val _methods = mutable.ArrayBuffer[Method]()
  private val methodNames = mutable.HashSet[String]()
  def getFirrtl: firrtl.CircuitState = {
    assert(_isElaborated, "You need to elaborate the module using UntimedModule(new ...)!")
    _firrtl.get
  }
  def methods: Seq[Method] = _methods
  // TODO: automagically infer names like Chisel does for its native constructs
  def fun(name: String) = {
    require(!methodNames.contains(name), s"Method $name already exists")
    methodNames += name
    NMethodBuilder(this, name)
  }
}

object UntimedModule {
  private val elaborating = new ThreadLocal[Boolean] { override def initialValue(): Boolean = false }
  def apply[M <: UntimedModule](m: => M): M = {
    // when elaborating, this acts like chisel3.Module(...)
    if(elaborating.get()) {
      val sub = Module {
        val mod = m
        // make sure all signals are marked as DontCare before the methods are generated
        mod._dontCareSignals.foreach(s => s := DontCare)
        // immediatly generate all methods for the submodule
        mod.methods.foreach(_.generate())
        mod
      }
      annotate(new ChiselAnnotation { override def toFirrtl = SubmoduleAnnotation(sub.toTarget, sub) })
      sub
    } else { // but it can also be used to elaborate the toplevel
      elaborate(m)
    }
  }
  def elaborate[M <: UntimedModule](m: => M): M = {
    elaborating.set(true)
    var opt: Option[M] = None
    val gen = () => {
      opt = Some(m)
      // make sure all signals are marked as DontCare before the methods are generated
      opt.get._dontCareSignals.foreach(s => s := DontCare)
      // generate the circuit for each method
      opt.get.methods.foreach(_.generate())
      opt.get
    }
    val fir = ChiselCompiler.toLowFirrtl(gen)
    val mod = opt.get
    mod._isElaborated = true
    mod._firrtl = Some(fir)
    elaborating.set(false)
    mod
  }
}

case class SubmoduleAnnotation(target: ModuleTarget, untimed: UntimedModule) extends SingleTargetAnnotation[ModuleTarget] {
  def duplicate(n: ModuleTarget) = this.copy(n)
}