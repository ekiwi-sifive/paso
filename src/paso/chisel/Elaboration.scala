// Copyright 2020 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package paso.chisel

import chisel3._
import chisel3.hacks.elaborateInContextOfModule
import firrtl.annotations.Annotation
import firrtl.ir.NoInfo
import firrtl.{ChirrtlForm, CircuitState, Compiler, CompilerUtils, HighFirrtlEmitter, HighForm, IRToWorkingIR, ResolveAndCheck, Transform, ir, passes}
import paso.verification.{Assertion, NamedExpr, PendingInputNode, ProtocolInterpreter, UntimedModel, VerificationGraph, VerificationProblem}
import paso.{Binding, UntimedModule}
import uclid.smt

/** essentially a HighFirrtlCompiler + ToWorkingIR */
class CustomFirrtlCompiler extends Compiler {
  val emitter = new HighFirrtlEmitter
  def transforms: Seq[Transform] =
    CompilerUtils.getLoweringTransforms(ChirrtlForm, HighForm) ++
        Seq(new IRToWorkingIR, new ResolveAndCheck, new firrtl.transforms.DedupModules)
}

object Elaboration {
  private def toFirrtl(gen: () => RawModule): (ir.Circuit, Seq[Annotation]) = {
    val chiselCircuit = Driver.elaborate(gen)
    val annos = chiselCircuit.annotations.map(_.toFirrtl)
    (Driver.toFirrtl(chiselCircuit), annos)
  }
  private val highFirrtlCompiler = new CustomFirrtlCompiler
  private def toHighFirrtl(c: ir.Circuit, annos: Seq[Annotation] = Seq()): (ir.Circuit, Seq[Annotation]) = {
    val st = highFirrtlCompiler.compile(CircuitState(c, ChirrtlForm, annos, None), Seq())
    (st.circuit, st.annotations)
  }
  private def lowerTypes(tup: (ir.Circuit, Seq[Annotation])): (ir.Circuit, Seq[Annotation]) = {
    val st = CircuitState(tup._1, ChirrtlForm, tup._2, None)
    // TODO: we would like to lower bundles but not vecs ....
    val st_no_bundles = passes.LowerTypes.execute(st)
    (st_no_bundles.circuit, st_no_bundles.annotations)
  }
  private def getMain(c: ir.Circuit): ir.Module = c.modules.find(_.name == c.main).get.asInstanceOf[ir.Module]

  private def elaborateMappings[IM <: RawModule, SM <: UntimedModule](
      impl: IM, impl_state: Seq[State],
      spec: SM, spec_state: Seq[State], maps: Seq[(IM, SM) => Unit]): Seq[Assertion] = {
    val map_ports = impl_state.map { st =>
      ir.Port(NoInfo, impl.name + "." + st.name, ir.Input, st.tpe)
    } ++ spec_state.map { st =>
      ir.Port(NoInfo, spec.name + "." + st.name, ir.Input, st.tpe)
    }
    val map_mod = ir.Module(NoInfo, name = "m", ports=map_ports, body=ir.EmptyStmt)

    maps.flatMap { m =>
      val mod = elaborateInContextOfModule(impl, spec, "map", {() => m(impl, spec)})
      val body = mod._1.modules.head.asInstanceOf[ir.Module].body
      val c = ir.Circuit(NoInfo, Seq(map_mod.copy(body=body)), map_mod.name)
      //val elaborated = lowerTypes(toHighFirrtl(c, mod._2))
      val elaborated = toHighFirrtl(c, mod._2)
      new FirrtlInvarianceInterpreter(elaborated._1, elaborated._2).run().asserts
    }
  }

  private def elaborateInvariances[IM <: RawModule](impl: IM, impl_state: Seq[State], invs: Seq[IM => Unit]): Seq[Assertion] = {
    val inv_ports = impl_state.map { st =>
      ir.Port(NoInfo, st.name, ir.Input, st.tpe)
    }
    val inv_mod = ir.Module(NoInfo, name = "i", ports=inv_ports, body=ir.EmptyStmt)

    invs.flatMap { ii =>
      val mod = elaborateInContextOfModule(impl, {() => ii(impl)})
      val body = mod._1.modules.head.asInstanceOf[ir.Module].body
      val c = ir.Circuit(NoInfo, Seq(inv_mod.copy(body=body)), inv_mod.name)
      val elaborated = lowerTypes(toHighFirrtl(c, mod._2))
      new FirrtlInvarianceInterpreter(elaborated._1, elaborated._2).run().asserts
    }
  }

  private def elaborateProtocols(protos: Seq[paso.Protocol]): Seq[(String, PendingInputNode)] = {
    protos.map{ p =>
      //println(s"Protocol for: ${p.methodName}")
      val (raw_firrtl, raw_annos) = toFirrtl(() => new MultiIOModule() { p.generate() })
      val (ff, annos) = lowerTypes(toHighFirrtl(raw_firrtl, raw_annos))
      val int = new ProtocolInterpreter
      new FirrtlProtocolInterpreter(p.methodName, ff, annos, int).run()
      (p.methodName, int.getGraph(p.methodName))
    }
  }

  private case class Impl[IM <: RawModule](mod: IM, state: Seq[State], model: smt.SymbolicTransitionSystem)
  private def elaborateImpl[IM <: RawModule](impl: => IM): Impl[IM] = {
    var ip: Option[IM] = None
    val (impl_c, impl_anno) = toFirrtl({() => ip = Some(impl); ip.get})
    val impl_fir = toHighFirrtl(impl_c, impl_anno)._1
    val impl_state = FindState(impl_fir).run()
    val impl_model = FirrtlToFormal(impl_fir, impl_anno)
    // cross check states:
    impl_state.foreach { state =>
      assert(impl_model.states.exists(_.sym.id == state.name), s"State $state is missing from the formal model!")
    }
    Impl(ip.get, impl_state, impl_model)
  }

  private case class Spec[SM <: UntimedModule](mod: SM, state: Seq[State], model: UntimedModel)
  private def elaborateSpec[SM <: UntimedModule](spec: => SM) = {
    var sp: Option[SM] = None
    val (main, _) = toFirrtl { () =>
      sp = Some(spec)
      sp.get
    }

    val spec_name = main.main
    val spec_state = FindState(main).run()

    val spec_module = getMain(main)
    val methods = sp.get.methods.map { meth =>
      val (raw_firrtl, raw_annos) = elaborateInContextOfModule(sp.get, meth.generate)

      // build module for this method:
      val method_body = getMain(raw_firrtl).body
      val comb_body = ir.Block(Seq(spec_module.body, method_body))
      val comb_c = ir.Circuit(NoInfo, Seq(spec_module.copy(body=comb_body)), spec_name)

      val (ff, annos) = toHighFirrtl(comb_c, raw_annos)
      val semantics = new FirrtlUntimedMethodInterpreter(ff, annos).run().getSemantics
      meth.name -> semantics
    }.toMap

    val spec_smt_state = spec_state.map{ st => smt.Symbol(st.name, firrtlToSmtType(st.tpe)) }
    val init = spec_state.collect{ case State(name, tpe, Some(init)) => NamedExpr(smt.Symbol(name, firrtlToSmtType(tpe)), init) }
    val untimed_model = UntimedModel(name = spec_name, state = spec_smt_state, methods = methods, init = init)
    Spec(sp.get, spec_state, untimed_model)
  }

  def apply[IM <: RawModule, SM <: UntimedModule](impl: => IM, spec: => SM, bind: (IM, SM) => Binding[IM, SM]): VerificationProblem = {

    val implementation = elaborateImpl(impl)
    val untimed = elaborateSpec(spec)

    // elaborate the binding
    val binding = bind(implementation.mod, untimed.mod)
    val protos = elaborateProtocols(binding.protos)
    val mappings = elaborateMappings(implementation.mod, implementation.state, untimed.mod, untimed.state, binding.maps)
    val invariances = elaborateInvariances(implementation.mod, implementation.state, binding.invs)

    // combine into verification problem
    val prob = VerificationProblem(
      impl = implementation.model,
      untimed = untimed.model,
      protocols = protos.toMap,
      invariances = invariances,
      mapping = mappings
    )

    prob
  }
}