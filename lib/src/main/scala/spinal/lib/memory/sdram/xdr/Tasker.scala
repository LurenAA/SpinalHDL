package spinal.lib.memory.sdram.xdr

import spinal.core._
import spinal.lib._

case class Tasker(cpa : CoreParameterAggregate) extends Component{
  import cpa._

  val io = new Bundle {
    val config = in(CoreConfig(cpa))
    val refresh = slave(Event)
    val inputs = Vec(cpp.map(cpp => slave(Stream(CoreCmd(cpp, cpa)))))
    val output = master(CoreTasks(cpa))
  }

  val readyForRefresh = True

  val banksRow = Mem(UInt(pl.sdram.rowWidth bits), pl.sdram.bankCount)

//  def Timing(loadValid : Bool, loadValue : UInt, timingWidth : Int = cp.timingWidth) = new Area{
//    val value = Reg(UInt(timingWidth bits)) init(0)
//    val notZero = value =/= 0
//    val busyNext = CombInit(notZero)
//    val busy = RegNext(busyNext)
//    value := value - notZero.asUInt.resized
//    when(loadValid) {
//      value := loadValue
////      if(timingWidth != 0) busyNext := True
//    }
//  }

  def Timing(loadValid : Bool, loadValue : UInt, timingWidth : Int = cp.timingWidth) = new Area{
    val value = Reg(UInt(timingWidth bits)) randBoot()
    val notZero = value =/= loadValue
    val busy = CombInit(notZero)
    value := value + notZero.asUInt.resized
    when(loadValid) {
      value := 0
    }
  }

  val CCD = (pl.beatCount > 1) generate Timing(io.output.ports.map(p => p.read || p.write).orR, pl.beatCount-2, log2Up(pl.beatCount))
  val RFC = Timing(io.output.refresh, io.config.RFC, cp.timingWidth+3)
  val RRD = Timing(io.output.ports.map(p => p.active).orR, io.config.RRD)
  val WTR = Timing(io.output.ports.map(p => p.write).orR, io.config.WTR)
  val RTW = Timing(io.output.ports.map(p => p.read).orR, io.config.RTW)
  val RP  = Timing(io.output.prechargeAll, io.config.RP + 1)
  val FAW = generation.FAW generate new Area{ //Can be optimized
    val trigger = io.output.ports.map(p => p.active).orR
    val ptr = RegInit(U"00")
    val slots = (0 to 3).map(i => Timing(ptr === i && trigger, io.config.FAW))
    val busyNext =  Vec(slots.map(_.busy)).read(ptr+1)
    ptr := ptr + U(trigger)
  }


  val banks = for(bankId <- 0 until pl.sdram.bankCount) yield new Area {
    val hits = B(io.output.ports.map(_.address.bank === bankId))
    def portEvent(f : CoreTask => Bool) = (hits & B(io.output.ports.map(f))).orR

    val activeNext = Bool
    val active = RegNext(activeNext) init(False)
    activeNext := active
    when(portEvent(p => p.precharge) || io.output.prechargeAll){
      activeNext := False
    }
    when(portEvent(p => p.active)){
      activeNext := True
    }

    val WR  = Timing(portEvent(p => p.write), io.config.WR)
    val RAS = Timing(portEvent(p => p.active), io.config.RAS)
    val RP  = Timing(portEvent(p => p.precharge), io.config.RP)
    val RCD = Timing(portEvent(p => p.active), io.config.RCD)
    val RTP = Timing(portEvent(p => p.read), io.config.RTP)

    val allowPrecharge = !WR.busy && !RAS.busy && !RTP.busy
    val allowActive = !RP.busy
    val allowWrite = !RCD.busy
    val allowRead = !RCD.busy
  }
  val allowPrechargeAll = banks.map(_.allowPrecharge).andR

  case class Status() extends Bundle {
    val bankActive = Bool()
    val bankHit = Bool()
    val allowPrecharge = Bool()
    val allowActive = Bool()
    val allowWrite = Bool()
    val allowRead = Bool()

    def patch(address: SdramAddress): Unit = {
      allowPrecharge clearWhen (!banks.map(_.allowPrecharge).read(address.bank))
      allowActive clearWhen (!banks.map(_.allowActive).read(address.bank))
      allowWrite clearWhen (!banks.map(_.allowWrite).read(address.bank))
      allowRead clearWhen (!banks.map(_.allowRead).read(address.bank))

      for (output <- io.output.ports) {
        when(output.address.bank === address.bank) {
          when(output.precharge) {
            bankActive := False
          }
          when(output.active) {
            bankActive := True
            bankHit := output.address.row === address.row
            allowRead := False
            allowWrite := False
            allowPrecharge := False
          }
          when(output.read || output.write) {
            allowPrecharge := False
          }
          when(output.precharge) {
            allowActive := False
          }
        }
      }
    }
  }

  case class Task() extends Bundle{
    val write = Bool()
    val address = UInt(pl.sdram.byteAddressWidth bits)
    val context = Bits(backendContextWidth bits)
    val burstLast = Bool()
    val portId = UInt(log2Up(cpp.size) bits)
  }

  val inputsArbiter = new Area{
    def inputs = io.inputs
    val output = Stream(Task())
    val state = RegInit(B(1, cpp.size bits))
    val inputsValids = B(inputs.map(_.valid))
    val selOH = OHMasking.roundRobin(inputsValids, state)

    val tocken = Reg(UInt(log2Up(cp.portTockenMax) bits)) init(0)
    val tockenIncrement = CombInit(output.ready)
    when(tockenIncrement){
      tocken := tocken + 1
    }
    when(!(inputs.map(_.valid).asBits & state).orR || tockenIncrement && ((inputs.map(_.burstLast).asBits & state).orR && tocken >= cp.portTockenMin || tocken >= cp.portTockenMax)){
      state := state.rotateLeft(1)
      tocken := 0
    }

    output.valid := (selOH & inputsValids).orR
    Vec(inputs.map(_.ready)) := Vec(selOH.asBools.map(_ && output.ready))
    val selPayload = MuxOH(selOH, inputs.map(_.payload.resized))
    output.write := selPayload.write
    output.address := selPayload.address
    output.context := selPayload.context
    output.burstLast := selPayload.burstLast
    output.portId := OHToUInt(selOH)

    readyForRefresh clearWhen(inputsValids.orR)
  }

  val taskConstructor = new Area {
    val s0 = new Area {
      def input = inputsArbiter.output
      val portAddress = input.address.as(SdramAddress(pl.sdram))
    }
    val s1 = new Area {
      val input = s0.input.stage()
      val address = input.address.as(SdramAddress(pl.sdram))
      val status = Status()
      status.allowPrecharge := True
      status.allowActive := True
      status.allowWrite := True
      status.allowRead := True
      status.bankHit := banksRow.readAsync(address.bank) === address.row
      status.bankActive := banks.map(_.active).read(address.bank)
      status.patch(address)

      readyForRefresh clearWhen(input.valid)
    }
  }

  val stations = for (stationId <- 0 until cp.stationCount) yield new Area {
    val id = stationId
    val othersMask = ((BigInt(1) << cp.stationCount)-1) - ((BigInt(1) << id))
    val valid = RegInit(False)
    val status = Reg(Status())
    val address = Reg(SdramAddress(cpa.pl.sdram))
    val write = Reg(Bool)
    val context = Reg(Bits(backendContextWidth bits))
    val portId = Reg(UInt(log2Up(cpa.cpp.size) bits))

    //Arbitration states vs other ports
    val stronger = Reg(Bits(cp.stationCount bits)) init(0)                  //Solve basic ordering
    val afterBank, afterAccess = Reg(Bits(cp.stationCount bits)) init(0)    //Solve port inner oder, bank conflicts across stations and read/write conflicts across stations

    import status._
    allowPrecharge := True
    allowActive := !RRD.busy && (if(generation.FAW) !FAW.busyNext else True)
    allowWrite := !RTW.busy && (if(CCD != null) !CCD.busy else True)
    allowRead := !WTR.busy &&  (if(CCD != null) !CCD.busy else True)
    status.patch(address)

    val inputMiss = !bankActive || !bankHit
    val inputActive = !bankActive
    val inputPrecharge = bankActive && !bankHit
    val inputAccess =  bankActive && bankHit
    val inputWrite =  bankActive && bankHit && write
    val inputRead  = bankActive && bankHit && !write
    val inibated = False

    val doActive = inputActive && allowActive
    val doPrecharge = inputPrecharge && allowPrecharge
    val doWrite = inputWrite && allowWrite
    val doRead = inputRead && allowRead
    val doSomething = valid && (doActive || doPrecharge || doWrite || doRead) && !inibated

    val cmdOutputPayload = CoreTask(cpa)
    io.output.ports(stationId).source := portId
    io.output.ports(stationId).address := address
    io.output.ports(stationId).context := context
    io.output.ports(stationId).active := inputActive
    io.output.ports(stationId).precharge := inputPrecharge
    io.output.ports(stationId).write := inputWrite
    io.output.ports(stationId).read := inputRead
  }

  val loader = new Area{
    val stationsValid = B(stations.map(_.valid))
    val stronger = CombInit(stationsValid)
    val afterBank = stationsValid & B(stations.map(s => s.address.bank === taskConstructor.s1.address.bank))
    val afterAccess = stationsValid & B(stations.map(s => s.portId === taskConstructor.s1.input.portId || s.write =/= taskConstructor.s1.input.write))
    taskConstructor.s1.input.ready := !stationsValid.andR
    val slot = for(station <- stations) yield new Area{
      val canSpawn = ~B(stations.take(station.id).map(_.valid)) === 0 && !station.valid
      when(taskConstructor.s1.input.valid && canSpawn) {
        station.valid     := True
        station.status    := taskConstructor.s1.status
        station.address   := taskConstructor.s1.address
        station.write     := taskConstructor.s1.input.write
        station.context   := taskConstructor.s1.input.context
        station.portId    := taskConstructor.s1.input.portId
        station.stronger  := stronger & station.othersMask
        station.afterBank := afterBank & station.othersMask
        station.afterAccess := afterBank & station.othersMask
      }
    }
  }

  val arbiter = new Area{
    val selOH = Bits(cp.stationCount bits)
    for(station <- stations) yield new Area {
      station.inibated setWhen(station.inputAccess && station.afterAccess.orR)
      station.inibated setWhen(station.inputMiss   && station.afterBank.orR)

      val othersDoSomething = B(stations.map(_.doSomething)) & station.stronger & B(station.othersMask)
      selOH(station.id) := station.doSomething && !othersDoSomething.orR
    }

    for((station, sel, port) <- (stations, selOH.asBools, io.output.ports).zipped){
      when(sel) {
        when(station.inputAccess){
          station.valid := False
        }
      } otherwise {
        port.read := False
        port.write := False
        port.active := False
        port.precharge := False
      }
    }

    val askRefresh = io.refresh.valid && readyForRefresh
    io.refresh.ready := False
    io.output.prechargeAll := False
    io.output.refresh := False
    val refreshState = RegInit(U"00")
    when(askRefresh){
      switch(refreshState){
        is(0) { //Dummy state to ensure allowPrechargeAll propagation
          refreshState := 1
        }
        is(1) {
          when(RegNext(allowPrechargeAll)) {
            io.output.prechargeAll := True
            refreshState := 2
          }
        }
        is(2) {
          when(!RP.busy) {
            io.output.refresh := True
            refreshState := 3
          }
        }
        is(3){
          when(!RFC.busy){
            io.refresh.ready := True
            refreshState := 0
          }
        }
      }
    }
  }


  val stationsPatch = for(station <- stations) yield new Area{
    import station._
    when(io.output.ports.map(_.active).orR){
      status.allowActive := False
    }

    if(CCD != null) when(io.output.ports.map(p => p.read || p.write).orR){
      status.allowRead := False
      status.allowWrite := False
    } else {
      when(io.output.ports.map(_.read).orR){
        status.allowWrite := False
      }
      when(io.output.ports.map(_.write).orR){
        status.allowRead := False
      }
    }
  }

  val selectedAddress = MuxOH(arbiter.selOH, io.output.ports.map(_.address))
  when(arbiter.selOH.orR){
    banksRow.write(selectedAddress.bank, selectedAddress.row)
  }
}
