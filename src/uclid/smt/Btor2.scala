/*
 * UCLID5 Verification and Synthesis Engine
 *
 * Copyright (c) 2020. The Regents of the University of California
 *
 * All Rights Reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Author: Kevin Laeufer <laeufer@cs.berkeley.edu>
 *
 */

package uclid.smt

import java.io.{File, PrintWriter}

import scala.io.Source
import scala.collection.mutable
import scala.util.matching.Regex
import scala.sys.process._

case class State(sym: Symbol, init: Option[Expr] = None, next: Option[Expr]= None)
case class SymbolicTransitionSystem(name: Option[String], inputs: Seq[Symbol], states: Seq[State],
                                    outputs: Seq[Tuple2[String,Expr]] = Seq(),
                                    constraints: Seq[Expr] = Seq(), bad: Seq[Expr] = Seq(), fair: Seq[Expr] = Seq()) {
  private def disjunction(props: Seq[Expr]): Seq[Expr] = if(props.isEmpty) {Seq()} else {
    Seq(props.reduce{ (a,b) => OperatorApplication(DisjunctionOp, List(a, b)) })
  }
  // ensures that the number of bad states is 1 or 0
  def unifyProperties(): SymbolicTransitionSystem = {
    this.copy(bad = disjunction(this.bad))
  }
}


object Btor2 {
  def load(filename: String): SymbolicTransitionSystem = {
    val ff = Source.fromFile(filename)
    val sys = read(ff.getLines())
    ff.close()
    sys
  }
  def read(lines: Iterator[String]): SymbolicTransitionSystem = Btor2Parser.read(lines)
  def serialize(sys: SymbolicTransitionSystem): Seq[String] = Btor2Serializer.serialize(sys, false)
  def serialize(sys: SymbolicTransitionSystem, skipOutput: Boolean): Seq[String] = Btor2Serializer.serialize(sys, skipOutput)
  def createBtorMC(): ModelChecker = new BtormcModelChecker()
  def createCosa2MC(): ModelChecker = new Cosa2ModelChecker()
}

trait ModelCheckResult {
  def isFail: Boolean
  def isSuccess: Boolean = !isFail
}
case class ModelCheckSuccess() extends ModelCheckResult { override def isFail: Boolean = false }
case class ModelCheckFail() extends ModelCheckResult { override def isFail: Boolean = true }


class BtormcModelChecker extends ModelChecker {
  // TODO: check to make sure binary exists
  override val name: String = "btormc"
  override val supportsOutput: Boolean = false
  override def makeArgs(kMax: Int, inputFile: Option[String]): Seq[String] = {
    val prefix = if(kMax > 0) Seq("btormc", s"--kmax $kMax") else Seq("btormc")
    inputFile match {
      case None => prefix
      case Some(file) => prefix ++ Seq(s"$file")
    }
  }
}

class Cosa2ModelChecker extends ModelChecker {
  // TODO: check to make sure binary exists
  override val name: String = "cosa2"
  override val supportsOutput: Boolean = true
  override val supportsMultipleProperties: Boolean = false
  override def makeArgs(kMax: Int, inputFile: Option[String]): Seq[String] = {
    val base = Seq("cosa2", "--engine bmc")
    val prefix = if(kMax > 0) base ++ Seq(s"--bound $kMax") else base
    inputFile match {
      case None => throw new RuntimeException("cosa2 only supports file based input. Please supply a filename!")
      case Some(file) => prefix ++ Seq(s"$file")
    }
  }
}

abstract class ModelChecker {
  val name: String
  def makeArgs(kMax: Int, inputFile: Option[String] = None): Seq[String]
  val supportsOutput: Boolean
  val supportsMultipleProperties: Boolean = true
  def check(sys: SymbolicTransitionSystem, kMax: Int = -1, fileName: Option[String] = None): ModelCheckResult = {
    val checkSys = if(supportsMultipleProperties) sys else sys.unifyProperties()
    fileName match {
      case None => checkWithPipe(checkSys, kMax)
      case Some(file) => checkWithFile(file, checkSys, kMax)
    }
  }

  private def checkWithFile(fileName: String, sys: SymbolicTransitionSystem, kMax: Int): ModelCheckResult = {
    val btorWrite = new PrintWriter(fileName)
    val lines = Btor2.serialize(sys, !supportsOutput)
    lines.foreach{l => btorWrite.println(l) }
    btorWrite.close()

    // execute model checker
    val resultFileName = fileName + ".out"
    val cmd = makeArgs(kMax, Some(fileName)).mkString(" ")
    println(cmd)
    val ret = (cmd #> new File(resultFileName)).!
    println(s"ret: $ret")

    // read result file
    val ff = Source.fromFile(resultFileName)
    val res = ff.getLines().toSeq
    ff.close()

    // check if it starts with sat
    if(res.nonEmpty && res.head.startsWith("sat")) {
      ModelCheckFail()
    } else {
      println("Does this look like success to you?")
      println(res.mkString("\n"))
      ModelCheckSuccess()
    }
  }

  private def checkWithPipe(sys: SymbolicTransitionSystem, kMax: Int): ModelCheckResult = {
    val checker = new uclid.InteractiveProcess(makeArgs(kMax).toList)
    val lines = Btor2.serialize(sys, !supportsOutput)
    lines.foreach{l => checker.writeInput(l) ; println(l)}
    checker.finishInput()
    val res = checker.readOutput()
    res match {
      case None => ModelCheckSuccess()
      case Some(msg) => assert(msg == "sat", msg) ; ModelCheckFail()
    }
  }
}


object Btor2Serializer {
  def serialize(sys: SymbolicTransitionSystem, skipOutput: Boolean): Seq[String] = {
    val expr_cache = mutable.HashMap[Expr, Int]()
    val sort_cache = mutable.HashMap[Type, Int]()
    val lines = mutable.ArrayBuffer[String]()
    var index = 1

    def line(l: String): Int = {
      val ii = index
      lines += s"$ii $l"
      index += 1
      ii
    }

    def comment(c: String): Unit = { lines += s"; $c" }

    // Type/Sort serialization
    def t(typ: Type): Int = sort_cache.getOrElseUpdate(typ,{typ match {
      case BoolType => line("sort bitvec 1")
      case BitVectorType(w) => line(s"sort bitvec $w")
      case ArrayType(List(index), value) => line(s"sort array ${t(index)} ${t(value)}")
      case other => throw new RuntimeException(s"Unsupported type: $other")
    }})

    // Expression serialization
    def s(expr: Expr): Int = expr_cache.getOrElseUpdate(expr, {expr match {
      case ArraySelectOperation(array, List(index)) =>
        line(s"read ${t(expr.typ)} ${s(array)} ${s(index)}")
      case ArrayStoreOperation(array, List(index), value) =>
        line(s"write ${t(expr.typ)} ${s(array)} ${s(index)} ${s(value)}")
      case Symbol(id, typ) =>
        throw new RuntimeException(s"Unknown symbol $id : $typ")
      case OperatorApplication(op, List(a)) => unary(op, a, expr.typ)
      case OperatorApplication(op, List(a, b)) => binary(op, a, b, expr.typ)
      case OperatorApplication(ITEOp, List(cond, a, b)) =>
        line(s"ite ${t(expr.typ)} ${s(cond)} ${s(a)} ${s(b)}")
      case BooleanLit(value) => lit(if(value) BigInt(1) else BigInt(0), 1)
      case BitVectorLit(value, w) => lit(value, w)
      case other => throw new NotImplementedError(s"TODO: implement serialization for $other")
    }})

    def lit(value: BigInt, w: Int): Int = {
      val typ = t(BitVectorType(w))
      lazy val mask = (BigInt(1) << w) - 1
      if(value == 0) line(s"zero $typ")
      else if(value == 1) line(s"one $typ")
      else if(value == mask) line(s"ones $typ")
      else {
        val digits = value.toString(2)
        val padded = digits.reverse.padTo(w, '0').reverse
        line(s"const $typ $padded")
      }
    }

    def unary(op: Operator, a: Expr, typ: Type): Int = op match {
      case NegationOp => line(s"not ${t(typ)} ${s(a)}")
      case BVNotOp(_) => line(s"not ${t(typ)} ${s(a)}")
      case BVExtractOp(hi, lo) => line(s"slice ${t(typ)} ${s(a)} $hi $lo")
      case BVZeroExtOp(_, by) => line(s"uext ${t(typ)} ${s(a)} $by")
      case BVSignExtOp(_, by) => line(s"sext ${t(typ)} ${s(a)} $by")
      case other => throw new NotImplementedError(s"TODO: implement conversion for $other")
    }

    def binary(op: Operator, a: Expr, b: Expr, typ: Type): Int = {
      val btor_op = op match {
        case IffOp => "iff"
        case ImplicationOp => "implies"
        case EqualityOp => "eq"
        case InequalityOp => "neq"
        case BVGTUOp(_) => "ugt"
        case BVGEUOp(_) => "ugte"
        case BVLTUOp(_) => "ult"
        case BVLEUOp(_) => "ulte"
        case BVGTOp(_) => "sgt"
        case BVGEOp(_) => "sgte"
        case BVLTOp(_) => "slt"
        case BVLEOp(_) => "slte"
        case BVAndOp(_) => "and"
        case ConjunctionOp => "and"
        case BVOrOp(_) => "or"
        case DisjunctionOp => "or"
        case BVXorOp(_) => "xor"
        case BVLeftShiftBVOp(_) => "sll"
        case BVARightShiftBVOp(_) => "sra"
        case BVLRightShiftBVOp(_) => "srl"
        case BVAddOp(_) => "add"
        case BVMulOp(_) => "mul"
        case BVUremOp(_) => "urem"
        case BVSremOp(_) => "srem"
        case BVSubOp(_) => "sub"
        case BVConcatOp(_) => "concat"
        case other => throw new NotImplementedError(s"TODO: support $other")
      }
      line(s"$btor_op ${t(typ)} ${s(a)} ${s(b)}")
    }

    val sortStatesByInitDependencies = true
    val states = if(sortStatesByInitDependencies) {
      val knownSymbols = sys.inputs.toSet
      val deps : Map[Symbol, Set[Symbol]] = sys.states.map { st =>
        st.sym -> st.init.toSeq.flatMap(Context.findSymbols).toSet.diff(knownSymbols).diff(Set(st.sym))
      }.toMap
      val dependencyGraph = firrtl.graph.DiGraph(deps).reverse
      val stateOrder = dependencyGraph.linearize
      val stateSymToState = sys.states.map{st => st.sym -> st}.toMap
      stateOrder.map(stateSymToState(_))
    } else { sys.states }

    // make sure that BV<1> and Bool alias to the same type
    sort_cache(BitVectorType(1)) = t(BoolType)

    // declare inputs
    sys.inputs.foreach { ii =>
      expr_cache(ii) = line(s"input ${t(ii.typ)} ${ii.id}")
    }

    // define state init and next
    states.foreach { st =>
      // calculate init expression before declaring the state
      // this is required by btormc (presumably to avoid cycles in the init expression)
      st.init.foreach{ init => comment(s"${st.sym}.init := $init") ; s(init) }

      expr_cache(st.sym) = line(s"state ${t(st.sym.typ)} ${st.sym.id}")

      st.init.foreach{ init => line(s"init ${t(init.typ)} ${s(st.sym)} ${s(init)}") }

      st.next.foreach{ next =>
        comment(s"${st.sym}.next := $next")
        line(s"next ${t(next.typ)} ${s(st.sym)} ${s(next)}")
      }
    }

    // define outputs first to allow other labels to refer to the output symbols
    sys.outputs.foreach{ case (name, expr) =>
      expr_cache(Symbol(name, expr.typ)) = s(expr)
      if(!skipOutput) line(s"output ${s(expr)} $name")
    }

    // define bad states, constraints and fairness properties
    val lbls = Seq("constraint" -> sys.constraints, "bad" -> sys.bad, "fair" -> sys.fair)
    lbls.foreach { case (lbl, exprs) => exprs.foreach{ e => line(s"$lbl ${s(e)}") } }

    lines
  }
}

object Btor2Parser {
  val unary = Set("not", "inc", "dec", "neg", "redand", "redor", "redxor")
  val binary = Set("iff", "implies", "sgt", "ugt", "sgte", "ugte", "slt", "ult", "slte", "ulte",
    "and", "nand", "nor", "or", "xnor", "xor", "rol", "ror", "sll", "sra", "srl", "add", "mul", "sdiv", "udiv", "smod",
    "srem", "urem", "sub", "saddo", "uaddo", "sdivo", "udivo", "smulo", "umulo", "ssubo", "usubo", "concat")

  def read(lines: Iterator[String]): SymbolicTransitionSystem = {
    val sorts = new mutable.HashMap[Int,Type]()
    val states = new mutable.HashMap[Int,State]()
    val inputs = new mutable.ArrayBuffer[Symbol]()
    val nodes = new mutable.HashMap[Int,Expr]()
    val labels = Seq("fair", "bad", "constraint", "output").map{l => l -> new mutable.ArrayBuffer[Tuple2[String, Expr]]()}.toMap
    val yosys_lables = new mutable.HashMap[Int,String]()

    // unique name generator
    val unique_names = new mutable.HashSet[String]()
    def is_unique(name: String): Boolean = !unique_names.contains(name)
    def unique_name(prefix: String): String = Iterator.from(0).map(i => s"_${prefix}_$i").filter(is_unique(_)).next

    // while not part of the btor2 spec, yosys annotates the systems name
    var name: Option[String] = None

    def to_bool(expr: Expr) = OperatorApplication(EqualityOp, List(expr, BitVectorLit(1,1)))
    def to_bv(expr: Expr) = OperatorApplication(ITEOp, List(expr, BitVectorLit(1,1),  BitVectorLit(0,1)))
    def to_bv_if_needed(expr: Expr) = expr.typ match { case BoolType => to_bv(expr) case _ => expr}

    def parse_sort(parts: Seq[String]): Type = {
      lazy val w1 = Integer.parseInt(parts(3))
      lazy val w2 = Integer.parseInt(parts(4))
      if(parts(2) == "bitvec") {
        if(w1 == 1) BoolType else BitVectorType(w1)
      } else {
        require(parts(2) == "array")
        ArrayType(List(sorts(w1)), sorts(w2))
      }
    }

    def parse_const(format: String, str: String): BigInt = format match {
      case "const" => BigInt(str, 2)
      case "constd" => BigInt(str)
      case "consth" => BigInt(str, 16)
    }

    def parse_unary(op: String, expr: Expr, w: Int): Expr =
      if(expr.typ.isBool) parse_unary_bool(op, expr) else parse_unary_bv(op, expr, w)

    def parse_unary_bv(op: String, expr: Expr, w: Int): Expr = {
      require(expr.typ.isBitVector)
      val expr_w = expr.typ.asInstanceOf[BitVectorType].width
      op match {
        case "not" => OperatorApplication(BVNotOp(w), List(expr))
        case "inc" => OperatorApplication(BVAddOp(w), List(expr, BitVectorLit(BigInt(1), w)))
        case "dec" => OperatorApplication(BVSubOp(w), List(expr, BitVectorLit(BigInt(1), w)))
        case "neg" => OperatorApplication(BVSubOp(w), List(BitVectorLit(BigInt(0), w), expr))
        case "redand" =>
          val mask = (BigInt(1) << expr_w) - 1
          OperatorApplication(EqualityOp, List(expr, BitVectorLit(mask, expr_w)))
        case "redor" =>
          OperatorApplication(InequalityOp, List(expr, BitVectorLit(0, expr_w)))
        case "redxor" => throw new RuntimeException("TODO: implement xor reduction")
        case other => throw new RuntimeException(s"Unknown unary op $other")
      }
    }

    // convert operations on 1bit BV to Bool on the fly
    def parse_unary_bool(op: String, expr: Expr): Expr = {
      require(expr.typ.isBool)
      op match {
        case "not" | "inc" | "dec" => OperatorApplication(NegationOp, List(expr))
        case "neg" => expr
      }
    }

    def parse_binary(op: String, a: Expr, b: Expr, w: Int): Expr =
      if(a.typ.isBool) parse_binary_bool(op, a, b) else parse_binary_bv(op, a, b, w)

    def parse_binary_bv(op: String, a: Expr, b: Expr, w: Int): Expr = {
      require(a.typ.isBitVector && b.typ.isBitVector)
      val a_w = a.typ.asInstanceOf[BitVectorType].width
      def app(op: Operator) = OperatorApplication(op, List(a, b))
      op match {
        case "ugt" => app(BVGTUOp(a_w))
        case "ugte" => app(BVGEUOp(a_w))
        case "ult" => app(BVLTUOp(a_w))
        case "ulte" => app(BVLEUOp(a_w))
        case "sgt" => app(BVGTOp(a_w))
        case "sgte" => app(BVGEOp(a_w))
        case "slt" => app(BVLTOp(a_w))
        case "slte" => app(BVLEOp(a_w))
        case "and" => app(BVAndOp(w))
        case "nand" => OperatorApplication(BVNotOp(w), List(app(BVAndOp(w))))
        case "nor" => OperatorApplication(BVNotOp(w), List(app(BVOrOp(w))))
        case "or" => app(BVOrOp(w))
        case "xnor" => OperatorApplication(BVNotOp(w), List(app(BVXorOp(w))))
        case "xor" => app(BVXorOp(w))
        case "rol" | "ror" => throw new NotImplementedError("TODO: implement rotates on bv<N>")
        case "sll" => app(BVLeftShiftBVOp(w))
        case "sra" => app(BVARightShiftBVOp(w))
        case "srl" => app(BVLRightShiftBVOp(w))
        case "add" => app(BVAddOp(w))
        case "mul" => app(BVMulOp(w))
        case "sdiv" | "udiv" => throw new NotImplementedError("TODO: implement division on bv<N>")
        case "smod" => throw new NotImplementedError("TODO: implement signed mod on bv<N>")
        case "srem" => app((BVSremOp(w)))
        case "urem" => app((BVUremOp(w)))
        case "sub" => app(BVSubOp(w))
        case other => throw new RuntimeException(s"Unknown binary op $other")
      }
    }

    def parse_binary_bool(op: String, a: Expr, b: Expr): Expr = {
      require(a.typ.isBool && b.typ.isBool)
      def conj(x: Expr, y: Expr) = OperatorApplication(ConjunctionOp, List(x,y))
      def disj(x: Expr, y: Expr) = OperatorApplication(DisjunctionOp, List(x,y))
      def not(x: Expr) = OperatorApplication(NegationOp, List(x))
      // TODO: add native xor support to SMTLanguage.scala
      def xor(x: Expr, y: Expr) = disj(conj(x, not(y)), conj(not(x), y))
      // see tests in BitVectorToBoolSpec for a check of these translation rules
      op match {
        case "iff" | "eq" => OperatorApplication(IffOp, List(a, b))
        case "implies" => OperatorApplication(ImplicationOp, List(a, b))
        case "neq"     => OperatorApplication(InequalityOp, List(a, b))
        case "ugt" | "slt" => conj(a, not(b))
        case "uge" | "sle" => disj(a, not(b))
        case "ult" | "sgt" => conj(not(a), b)
        case "ule" | "sge" => disj(not(a), b)
        case "and" | "mul" => conj(a, b)
        case "nand" => not(conj(a,b))
        case "nor" => not(disj(a, b))
        case "or" => disj(a, b)
        case "xnor" => not(xor(a, b))
        case "xor" | "add" | "sub" => xor(a, b)
        case "rol" | "ror" | "sll" | "sra" | "srl" => throw new NotImplementedError("TODO: implement shifts on bv<1>")
        case "sdiv" | "udiv" => throw new NotImplementedError("TODO: implement division on bv<1>")
        case other => throw new RuntimeException(s"Unknown binary op $other")
      }
    }

    /** yosys sometimes provides comments with human readable names for i/o/ and state signals **/
    def parse_yosys_comment(comment: String): Option[Tuple2[Int,String]] = {
      // yosys module name annotation
      if(comment.contains("Yosys") && comment.contains("for module ")) {
        val start = comment.indexOf("for module ")
        val mod_name = comment.substring(start + "for module ".length).dropRight(1)
        name = Some(mod_name)
      }
      val yosys_lbl: Regex = "\\s*;\\s*(\\d+) \\\\(\\w+)".r
      yosys_lbl.findFirstMatchIn(comment) match {
        case Some(m) => Some((Integer.parseInt(m.group(1)), m.group(2)))
        case None => None
      }
    }

    def parse_comment(comment: String): Unit = {
      parse_yosys_comment(comment) match {
        case Some((ii, lbl)) => yosys_lables(ii) = lbl
        case None => None
      }
    }

    def parse_line(line: String): Unit = {
      if(line.isEmpty) { /* skip blank lines */ return }
      if(line.startsWith(";")) { parse_comment(line);  return }
      val parts = line.split(" ")
      val id = Integer.parseInt(parts.head)

      // nodes that have an sid feature it in "position" 2
      def sort = sorts(Integer.parseInt(parts(2)))
      def width = sort match {
        case BoolType => 1
        case BitVectorType(w) => w
        case other => throw new RuntimeException(s"{other}")
      }
      // nodes besides output that feature nid
      def expr(offset: Int) = {
        require(parts.length > 3+offset, s"parts(${3+offset}) does not exist! $parts")
        val nid = Integer.parseInt(parts(3+offset))
        require(nodes.contains(nid), s"Unknown node #$nid")
        nodes(nid)
      }

      val cmd = parts(1)
      val new_expr = cmd  match {
        case "sort" => sorts.put(id, parse_sort(parts)) ; None
        case "input" =>
          val name = if(parts.length > 3) parts(3) else unique_name("input")
          require(is_unique(name))
          unique_names += name
          val input = Symbol(name, sort)
          inputs.append(input)
          Some(input)
        case lbl @ ("output" | "bad" | "constraint" | "fair") =>
          val name = if(parts.length > 3) parts(3) else unique_name(lbl)
          require(is_unique(name))
          unique_names += name
          labels(lbl) += (name -> expr(-1))
          None
        case "state" =>
          val name = if(parts.length > 3) parts(3) else unique_name("state")
          require(is_unique(name))
          unique_names += name
          val state = Symbol(name, sort)
          states.put(id, State(state))
          Some(state)
        case "next" =>
          val state_id = Integer.parseInt(parts(3))
          states.put(state_id, states(state_id).copy(next=Some(expr(1))))
          None
        case "init" =>
          val state_id = Integer.parseInt(parts(3))
          states.put(state_id, states(state_id).copy(init=Some(expr(1))))
          None
        case format @ ("const" | "constd" | "consth" | "zero" | "one") =>
          val value = if(format == "zero") BigInt(0) else if (format == "one") BigInt(1) else parse_const(format, parts(3))
          Some(sort match {
            case BitVectorType(w) => BitVectorLit(value, w)
            case BoolType => BooleanLit(value != 0)
            case other => throw new RuntimeException(s"TODO: deal with $other constants")
          })
        case "ones" =>
          Some(sort match {
            case BitVectorType(w) => BitVectorLit((BigInt(1) << w) - 1, w)
            case BoolType => BooleanLit(true)
            case other => throw new RuntimeException(s"TODO: deal with $other constants")
          })
        case ext @ ("uext" | "sext") =>
          val by = Integer.parseInt(parts(4))
          if(by > 0) {
            val op = if (ext == "uext") BVZeroExtOp(width, by) else BVSignExtOp(width, by)
            val e = to_bv_if_needed(expr(0))
            Some(OperatorApplication(op, List(e)))
          } else { Some(expr(0)) }
        case "slice" =>
          val msb = Integer.parseInt(parts(4))
          val lsb = Integer.parseInt(parts(5))
          val bits = OperatorApplication(BVExtractOp(msb, lsb), List(expr(0)))
          Some(if(msb == lsb) to_bool(bits) else bits)
        case op if unary.contains(op) =>
          Some(parse_unary(op, expr(0), width))
        case "eq" =>
          Some(OperatorApplication(EqualityOp, List(expr(0), expr(1))))
        case "neq" =>
          Some(OperatorApplication(InequalityOp, List(expr(0), expr(1))))
        case "concat" =>
          Some(OperatorApplication(BVConcatOp(width), List(to_bv_if_needed(expr(0)), to_bv_if_needed(expr(1)))))
        case op if binary.contains(op) =>
          Some(parse_binary(op, expr(0), expr(1), width))
        case "read" =>
          Some(ArraySelectOperation(expr(0), List(expr(1))))
        case "write" =>
          val (dest, index, value) = (expr(0), expr(1), expr(2))
          Some(ArrayStoreOperation(dest, List(index), value))
        case "ite" =>
          val (cond, a, b) = (expr(0), expr(1), expr(2))
          Some(OperatorApplication(ITEOp, List(cond, a, b)))
        case other =>
          throw new RuntimeException(s"Unknown command: $other")

      }
      new_expr match {
        case Some(expr: Expr) =>
          assert(expr.typ == sort, s"Unexpected expression type ($expr : ${expr.typ}) in line: $line!\nExpected: $sort")
          nodes.put(id, expr)
        case _ => None
      }
    }

    lines.foreach{ll => parse_line(ll.trim)}

    //println(yosys_lables)
    // TODO: use yosys_lables to fill in missing symbol names

    SymbolicTransitionSystem(name, inputs=inputs.toSeq, states=states.values.toSeq,
      outputs = labels("output").toSeq,
      constraints = labels("constraint").map(_._2).toSeq,
      bad = labels("bad").map(_._2).toSeq,
      fair = labels("fair").map(_._2).toSeq)
  }

}