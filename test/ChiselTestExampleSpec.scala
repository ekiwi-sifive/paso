import org.scalatest._
import chisel3._
import chiseltest._

// copied from the chiseltest upstream repo
class ChiselTestExampleSpec extends FlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Testers2"

  it should "test inputless sequential circuits" in {
    test(new Module {
      val io = IO(new Bundle {
        val out = Output(UInt(8.W))
      })
      val counter = RegInit(UInt(8.W), 0.U)
      counter := counter + 1.U
      io.out := counter
    }) { c =>
      c.io.out.expect(0.U)
      c.clock.step()
      c.io.out.expect(1.U)
      c.clock.step()
      c.io.out.expect(2.U)
      c.clock.step()
      c.io.out.expect(3.U)
    }
  }

  it should "test sequential circuits" in {
    test(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := RegNext(io.in, 0.U)
    }) { c =>
      c.io.in.poke(0.U)
      c.clock.step()
      c.io.out.expect(0.U)
      c.io.in.poke(42.U)
      c.clock.step()
      c.io.out.expect(42.U)
    }
  }

  it should "test reset" in {
    test(new Module {
      val io = IO(new Bundle {
        val in = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      io.out := RegNext(io.in, 0.U)
    }) { c =>
      c.io.out.expect(0.U)

      c.io.in.poke(42.U)
      c.clock.step()
      c.io.out.expect(42.U)

      c.reset.poke(true.B)
      c.io.out.expect(42.U)  // sync reset not effective until next clk
      c.clock.step()
      c.io.out.expect(0.U)

      c.clock.step()
      c.io.out.expect(0.U)

      c.reset.poke(false.B)
      c.io.in.poke(43.U)
      c.clock.step()
      c.io.out.expect(43.U)
    }
  }
}