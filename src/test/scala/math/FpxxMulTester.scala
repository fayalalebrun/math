package math

import org.scalatest.funsuite.AnyFunSuite

import math.FpxxTesterSupport._

import spinal.sim._
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.StreamDriver
import spinal.lib.sim.StreamMonitor

object FpxxMulTester extends AnyFunSuite {
    case class FpxxMulDut(config: FpxxConfig) extends Component {
        val dut = FpxxMul(FpxxMul.Options(cIn = config, pipeStages = 2))

        val op = slave(Stream(Vec(cloneOf(dut.io.input.payload.a), 2)))
        dut.io.input << op.map { payload =>
            val bundle = cloneOf(dut.io.input.payload)
            bundle.a := payload(0)
            bundle.b := payload(1)

            bundle
        }

        val res = master(cloneOf(dut.io.result))
        res << dut.io.result
    }
}

class FpxxMulTester extends AnyFunSuite {

    def mulTest(
        config: FpxxConfig,
        testLines: Iterator[String]
    ) {
        SimConfig.withIVerilog.withWave.noOptimisation
            .compile(BundleDebug.fpxxDebugBits(FpxxMulTester.FpxxMulDut(config)))
            .doSim { dut =>
                SimTimeout(1000000)
                val stimuli = parseHexCases(testLines, 2, config, config, false)
                    // Avoid smallest value since it may involve subnormal rounding
                    .filter { a => !(a._2.mant == 0 && a._2.exp == 1) }
                    // No denormals
                    .filter { a => !a._2.isDenormal && !a._1.map(_.isDenormal).reduce(_ || _) }

                testOperation(stimuli, dut.op, dut.res, dut.clockDomain)
            }
    }

    test("float16 multiplication") {
        mulTest(
          FpxxConfig.float16(),
          testfloatGen(Seq("f16_mul"))
        )
    }

    test("float32 multiplication") {
        mulTest(
          FpxxConfig.float32(),
          testfloatGen(Seq("f32_mul"))
        )
    }

    test("float8e5m2_fnuz -> e8 -> bfloat16 multiplication") {
        val inConfig  = FpxxConfig.float8_e5m2fnuz()
        val outConfig = FpxxConfig.bfloat16()

        SimConfig.withIVerilog.withWave
            .compile(BundleDebug.fpxxDebugBits(new Module {
                val input  = slave Stream (Vec(Fpxx(inConfig), 2))
                val result = master Stream (Fpxx(outConfig))

                // Fork input for both converters
                val inputForked = StreamFork(input, 2, synchronous = true)

                val aConv = FpxxConverter(FpxxConverter.Options(inConfig, FpxxConfig(8, 2)))
                val bConv = FpxxConverter(FpxxConverter.Options(inConfig, FpxxConfig(8, 2)))
                aConv.io.a << inputForked(0).map(_(0)).s2mPipe()
                bConv.io.a << inputForked(1).map(_(1)).s2mPipe()

                // Join the two converter outputs
                val joined = StreamJoin(aConv.io.r, bConv.io.r)

                val mult = FpxxMul(
                  FpxxMul.Options(FpxxConfig(8, 2), Some(outConfig), pipeStages = 2, rounding = RoundType.FLOOR)
                )
                mult.io.input << joined.translateWith {
                    val bundle = cloneOf(mult.io.input.payload)
                    bundle.a := aConv.io.r.payload
                    bundle.b := bConv.io.r.payload
                    bundle
                }

                mult.io.result >> result
            }))
            .doSim { dut =>
                SimTimeout(10000000)
                import scala.sys.process._
                val lines =
                    Process(Seq("python", "testgen.py", "float8_e5m2fnuz", "bfloat16", "mul")).lineStream.iterator

                testOperation(
                  parseHexCases(lines, 2, FpxxConfig.float8_e5m2fnuz(), FpxxConfig.bfloat16()),
                  dut.input,
                  dut.result,
                  dut.clockDomain
                )
            }
    }

}
