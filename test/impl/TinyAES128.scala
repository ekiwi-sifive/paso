// Copyright 2020 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

// Chisel port of the OpenCores TinyAES core


package impl

import chisel3._
import chisel3.util._

object Utils {
  def split(signal: UInt, by: Int): Vec[UInt] = {
    val subWidth = signal.getWidth / by
    require(subWidth * by == signal.getWidth)
    VecInit(Seq.tabulate(by)(ii => signal(subWidth*(ii+1)-1, subWidth*ii)).reverse)
  }
}

class TinyAES128 extends Module {
  val io = IO(new Bundle {
    val state = Input(UInt(128.W))
    val key = Input(UInt(128.W))
    val out = Output(UInt(128.W))
  })
  val s0 = RegNext(io.state ^ io.key)
  val k0 = RegNext(io.key)

  val k = Seq(k0) ++ Seq.tabulate(9)(_ => Wire(UInt(128.W)))
  val s = Seq(s0) ++ Seq.tabulate(9)(_ => Wire(UInt(128.W)))
  val kb = Seq.tabulate(10)(_ => Wire(UInt(128.W)))

  val eOuts = k.drop(1) ++ Seq(Wire(UInt(128.W)))
  k.zip(eOuts).zip(kb).zip(StaticTables.rcon).zipWithIndex.foreach {
    case ((((in, out), outDelayed), rcon), ii) => ExpandKey128(rcon, in, out, outDelayed).suggestName(s"a$ii")
  }

  val oOuts = s.drop(1) ++ Seq(Wire(UInt(128.W)))
  s.zip(kb).zip(oOuts).zipWithIndex.foreach{
    case (((state, key), stateNext), ii) => OneRound(state, key, stateNext).suggestName(s"r$ii")
  }

  val finalRound = Module(new FinalRound)
  finalRound.io.state := s(9)
  finalRound.io.key := kb(9)
  io.out := finalRound.io.stateNext
}

// https://en.wikipedia.org/wiki/AES_key_schedule
class ExpandKey128(val rcon: UInt) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(128.W))
    val out = Output(UInt(128.W))
    val outDelayed = Output(UInt(128.W))
  })
  val k = Utils.split(io.in, 4)
  val v0 = k(0)(31,24) ^ rcon ## k(0)(23,0)
  val v1 = v0 ^ k(1)
  val v2 = v1 ^ k(2)
  val v3 = v2 ^ k(3)

  val k0a = RegNext(v0)
  val k1a = RegNext(v1)
  val k2a = RegNext(v2)
  val k3a = RegNext(v3)

  val S4_0 = Module(new S4)
  S4_0.io.in := k(3)(23,0) ## k(3)(31,24)
  val k4a = S4_0.io.out

  val k0b = k0a ^ k4a
  val k1b = k1a ^ k4a
  val k2b = k2a ^ k4a
  val k3b = k3a ^ k4a

  io.out := k0b ## k1b ## k2b ## k3b
  io.outDelayed := RegNext(io.out)
}
object ExpandKey128 {
  def apply(rcon: Int, in: UInt, out: UInt, outDelayed: UInt): ExpandKey128 = {
    val m = Module(new ExpandKey128(rcon.U(8.W)))
    m.io.in := in
    out := m.io.out
    outDelayed := m.io.outDelayed
    m
  }
}

class RoundIO extends Bundle {
  val state = Input(UInt(128.W))
  val key = Input(UInt(128.W))
  val stateNext = Output(UInt(128.W))
}
trait HasRoundIO extends Module { val io: RoundIO ; val isFinal: Boolean }

class OneRound extends Module with HasRoundIO {
  val io = IO(new RoundIO)
  val isFinal = false

  val s = Utils.split(io.state, 4)
  val k = Utils.split(io.key, 4)

  val p = s.map{ sX =>
    val tl = Module(new TableLookup)
    tl.io.state := sX
    tl.io.p
  }

  val z0 = p(0)(0) ^ p(1)(1) ^ p(2)(2) ^ p(3)(3) ^ k(0)
  val z1 = p(0)(3) ^ p(1)(0) ^ p(2)(1) ^ p(3)(2) ^ k(1)
  val z2 = p(0)(2) ^ p(1)(3) ^ p(2)(0) ^ p(3)(1) ^ k(2)
  val z3 = p(0)(1) ^ p(1)(2) ^ p(2)(3) ^ p(3)(0) ^ k(3)

  io.stateNext := RegNext(z0 ## z1 ## z2 ## z3)
}

object OneRound {
  def apply(state: UInt, key: UInt, nextState: UInt): OneRound = {
    val m = Module(new OneRound)
    m.io.state := state
    m.io.key := key
    nextState := m.io.stateNext
    m
  }
}

class FinalRound extends Module with HasRoundIO {
  val io = IO(new RoundIO)
  val isFinal = true

    val s = Utils.split(io.state, 4)
    val k = Utils.split(io.key, 4)

    val p = s.map{ sX =>
      val s4 = Module(new S4)
      s4.io.in := sX
      Utils.split(s4.io.out, 4)
    }

    val z0 = (p(0)(0) ## p(1)(1) ## p(2)(2) ## p(3)(3)) ^ k(0)
    val z1 = (p(1)(0) ## p(2)(1) ## p(3)(2) ## p(0)(3)) ^ k(1)
    val z2 = (p(2)(0) ## p(3)(1) ## p(0)(2) ## p(1)(3)) ^ k(2)
    val z3 = (p(3)(0) ## p(0)(1) ## p(1)(2) ## p(2)(3)) ^ k(3)

    io.stateNext := RegNext(z0 ## z1 ## z2 ## z3)
}

class TableLookup extends Module {
  val io = IO(new Bundle {
    val state = Input(UInt(32.W))
    val p = Output(Vec(4, UInt(32.W)))
  })
  val b = Utils.split(io.state, 4)
  val t = Seq.tabulate(4)(_ => Module(new T))
  t.zip(b).foreach{case (t,b) => t.io.in := b}
  io.p(0) := t(0).io.out( 7,0) ## t(0).io.out(31, 8)
  io.p(1) := t(1).io.out(15,0) ## t(1).io.out(31,16)
  io.p(2) := t(2).io.out(23,0) ## t(2).io.out(31,24)
  io.p(3) := t(3).io.out
}

class IOBundle(inWidth: Int, outWidth: Int) extends Bundle {
  val in = Input(UInt(inWidth.W))

}

class S4 extends Module {
  val io = IO(new Bundle { val in = Input(UInt(32.W)) ; val out = Output(UInt(32.W)) })
  val S = Seq.tabulate(4)(_ => Module(new S))
  Utils.split(io.in, 4).zip(S).foreach { case (i, s) => s.io.in := i }
  io.out := S.map(_.io.out).reduce((a,b) => a ## b)
}

class T extends Module {
  val io = IO(new Bundle { val in = Input(UInt(8.W)) ; val out = Output(UInt(32.W)) })
  val s0 = Module(new S)
  s0.io.in := io.in
  val s4 = Module(new xS)
  s4.io.in := io.in
  io.out := s0.io.out ## s0.io.out ## (s0.io.out ^ s4.io.out) ## s4.io.out
}

class S extends Module {
  val io = IO(new Bundle { val in = Input(UInt(8.W)) ; val out = Output(UInt(8.W)) })
  io.out := RegNext(VecInit(StaticTables.S.map(_.U(8.W)))(io.in))
}
class xS extends Module {
  val io = IO(new Bundle { val in = Input(UInt(8.W)) ; val out = Output(UInt(8.W)) })
  io.out := RegNext(VecInit(StaticTables.xS.map(_.U(8.W)))(io.in))
}

object StaticTables {
  val rcon = Seq(0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36)
  val S  = Seq(
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76, 0xca, 0x82, 0xc9,
    0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0, 0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f,
    0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15, 0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07,
    0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75, 0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3,
    0x29, 0xe3, 0x2f, 0x84, 0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58,
    0xcf, 0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8, 0x51, 0xa3,
    0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2, 0xcd, 0x0c, 0x13, 0xec, 0x5f,
    0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73, 0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88,
    0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb, 0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac,
    0x62, 0x91, 0x95, 0xe4, 0x79, 0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a,
    0xae, 0x08, 0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a, 0x70,
    0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e, 0xe1, 0xf8, 0x98, 0x11,
    0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf, 0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42,
    0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16)
  require(S.length == 256)
  val xS = Seq(
    0xc6, 0xf8, 0xee, 0xf6, 0xff, 0xd6, 0xde, 0x91, 0x60, 0x02, 0xce, 0x56, 0xe7, 0xb5, 0x4d, 0xec, 0x8f, 0x1f, 0x89,
    0xfa, 0xef, 0xb2, 0x8e, 0xfb, 0x41, 0xb3, 0x5f, 0x45, 0x23, 0x53, 0xe4, 0x9b, 0x75, 0xe1, 0x3d, 0x4c, 0x6c, 0x7e,
    0xf5, 0x83, 0x68, 0x51, 0xd1, 0xf9, 0xe2, 0xab, 0x62, 0x2a, 0x08, 0x95, 0x46, 0x9d, 0x30, 0x37, 0x0a, 0x2f, 0x0e,
    0x24, 0x1b, 0xdf, 0xcd, 0x4e, 0x7f, 0xea, 0x12, 0x1d, 0x58, 0x34, 0x36, 0xdc, 0xb4, 0x5b, 0xa4, 0x76, 0xb7, 0x7d,
    0x52, 0xdd, 0x5e, 0x13, 0xa6, 0xb9, 0x00, 0xc1, 0x40, 0xe3, 0x79, 0xb6, 0xd4, 0x8d, 0x67, 0x72, 0x94, 0x98, 0xb0,
    0x85, 0xbb, 0xc5, 0x4f, 0xed, 0x86, 0x9a, 0x66, 0x11, 0x8a, 0xe9, 0x04, 0xfe, 0xa0, 0x78, 0x25, 0x4b, 0xa2, 0x5d,
    0x80, 0x05, 0x3f, 0x21, 0x70, 0xf1, 0x63, 0x77, 0xaf, 0x42, 0x20, 0xe5, 0xfd, 0xbf, 0x81, 0x18, 0x26, 0xc3, 0xbe,
    0x35, 0x88, 0x2e, 0x93, 0x55, 0xfc, 0x7a, 0xc8, 0xba, 0x32, 0xe6, 0xc0, 0x19, 0x9e, 0xa3, 0x44, 0x54, 0x3b, 0x0b,
    0x8c, 0xc7, 0x6b, 0x28, 0xa7, 0xbc, 0x16, 0xad, 0xdb, 0x64, 0x74, 0x14, 0x92, 0x0c, 0x48, 0xb8, 0x9f, 0xbd, 0x43,
    0xc4, 0x39, 0x31, 0xd3, 0xf2, 0xd5, 0x8b, 0x6e, 0xda, 0x01, 0xb1, 0x9c, 0x49, 0xd8, 0xac, 0xf3, 0xcf, 0xca, 0xf4,
    0x47, 0x10, 0x6f, 0xf0, 0x4a, 0x5c, 0x38, 0x57, 0x73, 0x97, 0xcb, 0xa1, 0xe8, 0x3e, 0x96, 0x61, 0x0d, 0x0f, 0xe0,
    0x7c, 0x71, 0xcc, 0x90, 0x06, 0xf7, 0x1c, 0xc2, 0x6a, 0xae, 0x69, 0x17, 0x99, 0x3a, 0x27, 0xd9, 0xeb, 0x2b, 0x22,
    0xd2, 0xa9, 0x07, 0x33, 0x2d, 0x3c, 0x15, 0xc9, 0x87, 0xaa, 0x50, 0xa5, 0x03, 0x59, 0x09, 0x1a, 0x65, 0xd7, 0x84,
    0xd0, 0x82, 0x29, 0x5a, 0x1e, 0x7b, 0xa8, 0x6d, 0x2c)
  require(xS.length == 256)
}