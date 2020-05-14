package aes

import chisel3._
import org.scalatest._
import paso._


trait AESHelperFunctions {
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

  def slice128To32(u: UInt): Seq[UInt] = {
    require(u.getWidth == 128)
    Seq(u(127,96), u(95, 64), u(63,32), u(31, 0))
  }

  def slice32To8(u: UInt): Seq[UInt] = {
    require(u.getWidth == 32)
    Seq(u(31,24), u(23, 16), u(15,8), u(7, 0))
  }

  def selectFromArray(i: UInt, data: Seq[UInt]): UInt = {
    data.zipWithIndex.foldLeft[UInt](data.head){case (prev, (value, index)) => Mux(i === index.U, value, prev)}
  }

  def S(i: UInt): UInt = {
    require(i.getWidth == 8)
    selectFromArray(i, S.map(_.U(8.W)))
  }

  def xS(i: UInt): UInt = {
    require(i.getWidth == 8)
    selectFromArray(i, xS.map(_.U(8.W)))
  }

  def S4(u: UInt): UInt = {
    require(u.getWidth == 32)
    S(u(31, 24)) ## S(u(23, 16)) ## S(u(15, 8)) ## S(u(7,0))
  }

  def T(u : UInt): Seq[UInt] = {
    require(u.getWidth == 8)
    val sl0 = S(u)
    val sl1 = sl0
    val sl3 = xS(u)
    val sl2 = sl1 ^ sl3

    Seq(sl0, sl1, sl2, sl3)
  }

  def tableLookup(s32: UInt): Seq[UInt] = {
    val b = slice32To8(s32)
    var rl = T(b(0))
    val p0 = rl(3) ## rl(0) ## rl(1) ## rl(2)
    rl = T(b(1))
    val p1 = rl(2) ## rl(3) ## rl(0) ## rl(1)
    rl = T(b(2))
    val p2 = rl(1) ## rl(2) ## rl(3) ## rl(0)
    rl = T(b(3))
    val p3 = rl(0) ## rl(1) ## rl(2) ## rl(3)

    Seq(p0, p1, p2, p3)
  }
}

// this is based on a translation of the ILA from
// https://github.com/PrincetonUniversity/IMDb/tree/master/tutorials/aes
class AESKeyExpansionSpec(rc: UInt) extends UntimedModule with AESHelperFunctions {
  require(rc.getWidth == 8)

  val expandKey128 = fun("expandKey128").in(UInt(128.W)).out(UInt(128.W)) { (in, out) =>
    val K = slice128To32(in)
    val v0 = K(0)(31, 24) ^ rc ## K(0)(23,0)
    val v1 = v0 ^ K(1)
    val v2 = v1 ^ K(2)
    val v3 = v2 ^ K(3)

    val k0a = v0
    val k1a = v1
    val k2a = v2
    val k3a = v3

    val k4a = S4(K(3)(23, 0) ## K(3)(31, 24))

    val k0b = k0a ^ k4a
    val k1b = k1a ^ k4a
    val k2b = k2a ^ k4a
    val k3b = k3a ^ k4a

    out := k0b ## k1b ## k2b ## k3b
  }
}

class RoundIn extends Bundle {
  val key = UInt(128.W)
  val state = UInt(128.W)
}
trait IsRoundSpec extends UntimedModule{ val round : IOMethod[RoundIn, UInt]  }

class AESRoundSpec extends UntimedModule with AESHelperFunctions with IsRoundSpec {
  val round= fun("round").in(new RoundIn).out(UInt(128.W)) { (in, nextState) =>
    val K0_4 = slice128To32(in.key)
    val S0_4 = slice128To32(in.state)

    val p0 = tableLookup(S0_4(0))
    val p1 = tableLookup(S0_4(1))
    val p2 = tableLookup(S0_4(2))
    val p3 = tableLookup(S0_4(3))

    val z0 = p0(0) ^ p1(1) ^ p2(2) ^ p3(3) ^ K0_4(0)
    val z1 = p0(3) ^ p1(0) ^ p2(1) ^ p3(2) ^ K0_4(1)
    val z2 = p0(2) ^ p1(3) ^ p2(0) ^ p3(1) ^ K0_4(2)
    val z3 = p0(1) ^ p1(2) ^ p2(3) ^ p3(0) ^ K0_4(3)

    nextState := z0 ## z1 ## z2 ## z3
  }
}

class AESFinalRoundSpec extends UntimedModule with AESHelperFunctions with IsRoundSpec {
  val round = fun("round").in(new RoundIn).out(UInt(128.W)) { (in, nextState) =>
    val K0_4 = slice128To32(in.key)
    val S0_4 = slice128To32(in.state)

    val p0 = slice32To8(S4(S0_4(0)))
    val p1 = slice32To8(S4(S0_4(1)))
    val p2 = slice32To8(S4(S0_4(2)))
    val p3 = slice32To8(S4(S0_4(3)))

    val z0 = (p0(0) ## p1(1) ## p2(2) ## p3(3)) ^ K0_4(0)
    val z1 = (p1(0) ## p2(1) ## p3(2) ## p0(3)) ^ K0_4(1)
    val z2 = (p2(0) ## p3(1) ## p0(2) ## p1(3)) ^ K0_4(2)
    val z3 = (p3(0) ## p0(1) ## p1(2) ## p2(3)) ^ K0_4(3)

    nextState := z0 ## z1 ## z2 ## z3
  }
}

class TinyAESRoundProtocol(impl: HasRoundIO) extends ProtocolSpec[IsRoundSpec] {
  val spec = if(impl.isFinal) new AESFinalRoundSpec else new AESRoundSpec

  protocol(spec.round)(impl.io) { (clock, dut, in, out) =>
    dut.state.poke(in.state)
    clock.stepAndFork()
    dut.state.poke(DontCare)
    dut.key.poke(in.key)
    clock.step()
    dut.key.poke(DontCare)
    dut.stateNext.expect(out)
  }
}

class TinyAESExpandKeyProtocol(impl: ExpandKey128) extends ProtocolSpec[AESKeyExpansionSpec] {
  val spec = new AESKeyExpansionSpec(impl.rcon)

  protocol(spec.expandKey128)(impl.io) { (clock, dut, in, out) =>
    dut.in.poke(in)
    clock.stepAndFork()
    dut.in.poke(DontCare)
    dut.out.expect(out)
    clock.step()
    dut.outDelayed.expect(out)
  }
}

class AES128Spec extends UntimedModule with AESHelperFunctions {
  val round = UntimedModule(new AESRoundSpec)
  val finalRound = UntimedModule(new AESFinalRoundSpec)
  val expand = rcon.map(r => UntimedModule(new AESKeyExpansionSpec(r.U(8.W))))

  val aes128 = fun("aes128").in(new RoundIn).out(UInt(128.W)) { (in, out) =>
    val r = Seq.tabulate(10)(_ => Wire(new RoundIn))

    // first round
    r(0).state := in.state ^ in.key
    r(0).key := expand(0).expandKey128(in.key)

    // mid rounds
    (0 until 10).foreach { ii =>
      r(ii + 1).state := round.round(r(ii))
      r(ii + 1).key := expand(ii + 1).expandKey128(r(ii).key)
    }

    // final round
    out := finalRound.round(r(10))
  }
}

class TinyAESProtocol(impl: TinyAES128) extends ProtocolSpec[AES128Spec] {
  val spec = new AES128Spec

  protocol(spec.aes128)(impl.io) { (clock, dut, in, out) =>
    // apply state and key for one cycle
    dut.state.poke(in.state)
    dut.key.poke(in.key)
    clock.stepAndFork()
    dut.state.poke(DontCare)
    dut.key.poke(DontCare)

    // wait 10 cycles
    (0 until 10).foreach(_ => clock.step())
    dut.out.expect(out)
  }
}

class TinyAESSpec extends FlatSpec {
  "TinyAES OneRound" should "refine its spec" in {
    Paso(new OneRound)(new TinyAESRoundProtocol(_)).proof()
  }

  "TinyAES FinalRound" should "refine its spec" in {
    Paso(new FinalRound)(new TinyAESRoundProtocol(_)).proof()
  }

  "TinyAES ExpandKey128" should "refine its spec" in {
    StaticTables.rcon.foreach { ii =>
      val rc = ii.U(8.W)
      Paso(new ExpandKey128(rc))(new TinyAESExpandKeyProtocol(_)).proof()
    }
  }

  "TinyAES128" should "correctly connect all submodules" in {
    Paso(new TinyAES128)(new TinyAESProtocol(_))(new SubSpecs(_, _) {
      replace(impl.finalRound)(new TinyAESRoundProtocol(_)).bind(spec.finalRound)
      impl.rounds.foreach(r => replace(r)(new TinyAESRoundProtocol(_)).bind(spec.round))
      impl.expandKey.zip(spec.expand).foreach{ case (i,s) => replace(i)(new TinyAESExpandKeyProtocol(_)).bind(s) }
    }).proof()
  }

}
