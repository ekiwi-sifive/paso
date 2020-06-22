// Copyright 2020, SiFive, Inc
// released under Apache License Version 2.0
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package assertions


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
class AssertionSpec {

}
