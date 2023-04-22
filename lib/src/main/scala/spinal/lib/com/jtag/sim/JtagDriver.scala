package spinal.lib.com.jtag.sim

import spinal.core.sim._
import spinal.lib.com.jtag.Jtag

/*
    * Jtag Driver tool
    * Author: Alexis Marquet
*/
case class JtagDriver(jtag: Jtag, clockPeriod: Long) {
  object JtagStates extends Enumeration {
    val RESET, IDLE,
    IR_SELECT, IR_CAPTURE, IR_SHIFT, IR_EXIT1, IR_PAUSE, IR_EXIT2, IR_UPDATE,
    DR_SELECT, DR_CAPTURE, DR_SHIFT, DR_EXIT1, DR_PAUSE, DR_EXIT2, DR_UPDATE, UNKNOWN = Value
  }
  var tapState = JtagStates.UNKNOWN
  def tapStep(tms: Boolean): Unit = {
    val oldState = tapState
    tapState = tapState match {
      case JtagStates.RESET => if (tms) JtagStates.RESET else JtagStates.IDLE
      case JtagStates.IDLE => if (tms) JtagStates.DR_SELECT else JtagStates.IDLE
      case JtagStates.DR_SELECT => if (tms) JtagStates.IR_SELECT else JtagStates.DR_CAPTURE
      case JtagStates.DR_CAPTURE => if (tms) JtagStates.IR_SELECT else JtagStates.DR_SHIFT
      case JtagStates.DR_SHIFT => if (tms) JtagStates.DR_EXIT1 else JtagStates.DR_SHIFT
      case JtagStates.DR_EXIT1 => if (tms) JtagStates.DR_UPDATE else JtagStates.DR_PAUSE
      case JtagStates.DR_PAUSE => if (tms) JtagStates.DR_EXIT2 else JtagStates.DR_PAUSE
      case JtagStates.DR_EXIT2 => if (tms) JtagStates.DR_UPDATE else JtagStates.DR_SHIFT
      case JtagStates.DR_UPDATE => if (tms) JtagStates.DR_SELECT else JtagStates.IDLE
      case JtagStates.IR_SELECT => if (tms) JtagStates.RESET else JtagStates.IR_CAPTURE
      case JtagStates.IR_CAPTURE => if (tms) JtagStates.RESET else JtagStates.IR_SHIFT
      case JtagStates.IR_SHIFT => if (tms) JtagStates.IR_EXIT1 else JtagStates.IR_SHIFT
      case JtagStates.IR_EXIT1 => if (tms) JtagStates.IR_UPDATE else JtagStates.IR_PAUSE
      case JtagStates.IR_PAUSE => if (tms) JtagStates.IR_EXIT2 else JtagStates.IR_PAUSE
      case JtagStates.IR_EXIT2 => if (tms) JtagStates.IR_UPDATE else JtagStates.IR_SHIFT
      case JtagStates.IR_UPDATE => if (tms) JtagStates.DR_SELECT else JtagStates.IDLE
      case JtagStates.UNKNOWN => JtagStates.UNKNOWN
    }
  }

  /*
    * Reset the JTAG tap into Test-Logic-Reset state
   */
  def doResetTap(): Unit = {
    doTmsSeq(Seq(true, true, true, true, true))
    tapState = JtagStates.RESET
  }

  /*
    * Send a sequence of TMS bits to move the JTAG tap into a specific state
   */
  def doTmsSeq(tmsSeq: Seq[Boolean]): Unit = {
    for (tms <- tmsSeq) {
      jtag.tms #= tms
      doClockCycle(1)
    }
  }

  /*
    * Send a clock cycle to the JTAG tap
   */
  def doClockCycle(n: Long): Unit = {
    for (_ <- 0 until n.toInt) {
      jtag.tck #= true
      tapStep(jtag.tms.toBoolean)
      sleep(clockPeriod / 2)
      jtag.tck #= false
      sleep(clockPeriod / 2)
    }
  }

  /*
    * Send a sequence of TDI bits to the JTAG tap and return the TDO sequence
    * param: flipTms is used to flip the last TMS bit to 1 when the scan chain is finished
    * This allows to write longer sequences of bits from multiple calls to this function
   */
  def doScanChain(tdiSeq: Seq[Boolean], flipTms: Boolean): Seq[Boolean] = {
    var tdoSeq = Seq.fill(tdiSeq.length)(false)
    for (i <- 0 until tdiSeq.length) {
      jtag.tdi #= tdiSeq(i)
      jtag.tms #= (i == tdiSeq.length - 1 && flipTms)
      tdoSeq = tdoSeq.updated(i, jtag.tdo.toBoolean)
      doClockCycle(1)
    }
    tdoSeq
  }
}