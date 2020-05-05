// Copyright 2020 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package paso.verification

import uclid.smt

case class MethodSemantics(guard: smt.Expr, updates: Seq[NamedExpr], outputs: Seq[NamedGuardedExpr], inputs: Seq[smt.Symbol])
case class UntimedModel(name: String, state: Seq[smt.State], methods: Map[String, MethodSemantics])

object UntimedModel {
  def functionAppSubs(m: UntimedModel): Iterable[(smt.Symbol, smt.FunctionApplication)] = m.methods.flatMap { case( _, meth) =>
    meth.outputs.flatMap(o => Seq(o.sym -> o.functionApp, o.guardSym -> o.guardFunctionApp)) ++
      meth.updates.map(u => u.sym -> u.functionApp)
  }
  def functionDefs(m: UntimedModel): Iterable[smt.DefineFun] = m.methods.flatMap { case( _, meth) =>
    meth.outputs.flatMap(o => Seq(o.functionDef, o.guardFunctionDef)) ++ meth.updates.map(_.functionDef)
  }
}

trait IsFunction {
  val sym: smt.Symbol
  val expr: smt.Expr
  private lazy val args = smt.Context.findSymbols(expr).toList
  private lazy val funSym = smt.Symbol(sym.id, smt.MapType(args.map(_.typ), expr.typ))
  def functionApp: smt.FunctionApplication = smt.FunctionApplication(funSym, args)
  def functionDef: smt.DefineFun = smt.DefineFun(funSym, args, expr)
}

case class NamedExpr(sym: smt.Symbol, expr: smt.Expr) extends IsFunction
case class NamedGuardedExpr(sym: smt.Symbol, expr: smt.Expr, guard: smt.Expr) extends IsFunction {
  val guardSym = smt.Symbol(sym.id + ".valid", smt.BoolType)
  private lazy val guardArgs = smt.Context.findSymbols(guard).toList
  private lazy val guardFunSym = smt.Symbol(guardSym.id, smt.MapType(guardArgs.map(_.typ), smt.BoolType))
  def guardFunctionApp: smt.FunctionApplication = smt.FunctionApplication(guardFunSym, guardArgs)
  def guardFunctionDef: smt.DefineFun = smt.DefineFun(guardFunSym, guardArgs, guard)
}
