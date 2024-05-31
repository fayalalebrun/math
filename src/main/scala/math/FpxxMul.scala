package math

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

object FpxxMul {
    import caseapp._

    case class Options(
        @HelpMessage(cli.Cli.fpxxConfigHelpMsg)
        cIn: FpxxConfig,
        @HelpMessage(cli.Cli.fpxxConfigHelpMsg)
        cOut: Option[FpxxConfig] = None,
        @HelpMessage(cli.Cli.stageMaskHelpMsg(3))
        pipeStages: StageMask = 0,
        @HelpMessage(cli.Cli.roundTypeHelpMsg)
        rounding: RoundType = RoundType.ROUNDTOEVEN
    )
}

case class FpxxMul(o: FpxxMul.Options) extends Component {

    val cOutU      = o.cOut getOrElse o.cIn
    def pipeStages = o.pipeStages

    assert(o.cIn.ieee_like && cOutU.ieee_like, "Can only handle IEEE compliant floats")
    assert(o.cIn.exp_size == cOutU.exp_size, "Can only handle equal input and output exponents")

    val io = new Bundle {
        val input = slave Flow (new Bundle {
            val a = Fpxx(o.cIn)
            val b = Fpxx(o.cIn)
        })
        val result = master Flow (Fpxx(cOutU))
    }

    val n0 = new Node {
        arbitrateFrom(io.input)

        val a = insert(io.input.payload.a)
        val b = insert(io.input.payload.b)
        val is_nan = insert(
          a.is_nan() || b.is_nan() || a.is_zero() && b.is_infinite() || b.is_zero() && a.is_infinite()
        )
        val is_inf    = insert(a.is_infinite() || b.is_infinite())
        val a_is_zero = insert(a.is_zero() || a.is_subnormal())
        val b_is_zero = insert(b.is_zero() || b.is_subnormal())
        val is_zero   = insert(a_is_zero || b_is_zero)

        val mant_a   = insert(U(1, 1 bits) @@ a.mant)
        val mant_b   = insert(U(1, 1 bits) @@ b.mant)
        val sign_mul = insert(a.sign ^ b.sign)
    }

    val n1 = new Node {
        val exp_mul  = insert((n0.a.exp +^ n0.b.exp).intoSInt - o.cIn.bias)
        val mant_mul = insert(n0.mant_a * n0.mant_b)
    }

    val n2 = new Node {
        arbitrateTo(io.result)

        val mant_mul_adj =
            ((n1.mant_mul @@ U(0, 1 bit)) |>> n1.mant_mul.msb.asUInt)(0, n1.mant_mul.getBitsWidth + 1 - 2 bits)

        val mant_mul_rounded =
            mant_mul_adj.fixTo(mant_mul_adj.getWidth downto mant_mul_adj.getWidth - cOutU.mant_size, o.rounding)

        val exp_mul_adj = n1.exp_mul + n1.mant_mul.msb.asUInt.intoSInt + mant_mul_rounded.msb.asUInt.intoSInt

        val result = io.result.payload

        result.sign := n0.sign_mul
        when(n0.is_nan) {
            result.set_nan()
        }.elsewhen(n0.is_inf) {
            result.set_inf()
        }.elsewhen(n0.is_zero || exp_mul_adj <= 0) {
            result.set_zero()
        }.elsewhen(exp_mul_adj >= result.exp.maxValue) {
            result.set_inf()
        }.otherwise {
            result.exp  := exp_mul_adj.asUInt.resized
            result.mant := mant_mul_rounded.resized
        }
    }

    implicit val maskConfig = StageMask.Config(2, List(0, 1))
    Builder(o.pipeStages(Seq(n0, n1, n2)))
}
