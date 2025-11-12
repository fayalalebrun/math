package math

import org.scalatest.funsuite.AnyFunSuite

import math.FpxxTesterSupport._

import spinal.sim._
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.StreamDriver
import spinal.lib.sim.StreamMonitor

class Fpxx2AFixTester extends AnyFunSuite {

    test("Fpxx2AFix Q44.12") {

        val config = FpxxConfig.float64()
        val stimuli = Array[Double](
          0.0,
          1.0,
          -1.0,
          4.8828125e-4,
          0.5,
          20.0,
          50.0,
          32767.5,
          32768.0,
          -32767.5,
          -32768.0,
          65535.5,
          65536.0,
          -65535.5,
          -65536.0,
          (1L << 43).toDouble + 1.0,
          -((1L << 32).toDouble),
          -1000.0,
          127.00390625,
          0.000244140625
        )

        var compiled = SimConfig.withIVerilog.withWave
            .compile(BundleDebug.fpxxDebugBits(new Fpxx2AFix(44 bits, 12 bits, config)))

        compiled.doSim { dut =>
            dut.clockDomain.forkStimulus(period = 10)
            SimTimeout(100000)
            val scoreboard = ScoreboardInOrder[FpxxHost]

            val cases = stimuli.iterator

            // Drive the output ready signal
            dut.io.result.ready #= true

            StreamDriver(dut.io.op, dut.clockDomain) { payload =>
                if (!cases.isEmpty) {
                    val a = cases.next()
                    payload #= a
                    scoreboard.pushRef(a)
                    true
                } else false
            }

            StreamMonitor(dut.io.result, dut.clockDomain) { payload =>
                assert(!payload.overflow.toBoolean)
                scoreboard.pushDut(payload.number.toDouble)
            }

            dut.clockDomain.forkStimulus(2)
            dut.clockDomain.waitActiveEdgeWhere(cases.isEmpty && scoreboard.ref.isEmpty)
        }
    }

}
