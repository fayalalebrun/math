package math

import org.scalatest.funsuite.AnyFunSuite

import math.FpxxTesterSupport._

import spinal.sim._
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.core.formal._

object FpxxAddTester {

    case class FpxxAddDut(config: FpxxConfig) extends Component {
        val op     = slave Stream (Vec(Fpxx(config), 2))
        val result = master Stream (Fpxx(config))

        // Fork the input stream for the adder and equivalence checking
        val opForked = StreamFork(op, 3, synchronous = true)

        val inner = new FpxxAdd(FpxxAdd.Options(config))
        inner.io.op << opForked(0).translateWith {
            val bundle = cloneOf(inner.io.op.payload)
            bundle.a := opForked(0).payload(0)
            bundle.b := opForked(0).payload(1)
            bundle
        }

        val addDelay = LatencyAnalysis(inner.io.op.valid, inner.io.result.valid)

        val equiv = new Area {
            val toFix = List.tabulate(2) { i =>
                val conv = new Fpxx2AFix(
                  (result.payload.exp.maxValue - config.bias + 3) bits,
                  config.bias + config.mant_size bits,
                  config,
                  if (addDelay > 2) 1 else 0,
                  true
                )
                conv.io.op << opForked(i + 1).map(_(i)).s2mPipe()
                conv.io.result
            }

            // Join the two streams properly using StreamJoin
            val joined = StreamJoin(toFix(0), toFix(1))

            val sum = joined.translateWith {
                val bundle = cloneOf(toFix(0).payload)
                bundle.number := (toFix(0).payload.number + toFix(1).payload.number).truncated
                bundle.flags.inf := toFix(0).payload.flags.inf || toFix(1).payload.flags.inf
                bundle.flags.nan := toFix(0).payload.flags.nan || toFix(1).payload.flags.nan
                bundle.flags.sign := toFix(0).payload.flags.sign || toFix(1).payload.flags.sign
                bundle.overflow := False
                bundle
            }

            val toFpxx = new AFix2Fpxx(
              result.payload.exp.maxValue - config.bias + 3 bits,
              config.bias + config.mant_size bits,
              config,
              scala.math.min(addDelay, 2),
              true
            )

            toFpxx.io.op.translateFrom(sum) { (to, from) =>
                to.number := from.number
                to.flags := from.flags
            }
            toFpxx.io.result.freeRun()

            val fixedDelay = LatencyAnalysis(op.valid, toFpxx.io.result.valid)

            val fixRes = Delay(toFpxx.io.result, addDelay - fixedDelay)

            when(pastValidAfterReset()) {
                assert(fixRes.valid === inner.io.result.valid, "Valid should be equal")
                when(fixRes.valid) {
                    assert(
                      fixRes.payload === inner.io.result.payload ||
                          (fixRes.is_nan() || fixRes.is_infinite()) && (inner.io.result.is_nan() || inner.io.result
                              .is_infinite()) || fixRes.is_zero() && inner.io.result.is_zero(),
                      "Fixed and adder outputs should match"
                    )
                }
            }
        }

        result << inner.io.result
    }
}

class FpxxAddTester extends AnyFunSuite {

    test("add float16") {
        val config = FpxxConfig.float16()

        SimConfig.withIVerilog.withWave.noOptimisation
            .compile(BundleDebug.fpxxDebugBits(FpxxAddTester.FpxxAddDut(config)))
            .doSim { dut =>
                SimTimeout(1000000)
                val stimuli = parseHexCases(testfloatGen(Seq("f16_add")), 2, config, config, false)
                    // No denormals
                    .filter { a => !a._2.isDenormal && !a._1.map(_.isDenormal).reduce(_ || _) }
                testOperation(stimuli, dut.op, dut.result, dut.clockDomain)
            }
    }

    test("add float32") {
        val config = FpxxConfig.float32()

        SimConfig.withIVerilog.withWave.noOptimisation
            .compile(BundleDebug.fpxxDebugBits(FpxxAddTester.FpxxAddDut(config)))
            .doSim { dut =>
                SimTimeout(1000000)
                val stimuli = parseHexCases(testfloatGen(Seq("f32_add")), 2, config, config, false)
                    // No denormals
                    .filter { a => !a._2.isDenormal && !a._1.map(_.isDenormal).reduce(_ || _) }
                testOperation(stimuli, dut.op, dut.result, dut.clockDomain)
            }
    }

}
