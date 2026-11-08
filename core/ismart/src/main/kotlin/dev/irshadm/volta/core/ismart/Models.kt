// Ported to Kotlin from mg-ismart-india-client by John Lazarus (MIT licence):
// https://github.com/john-lazarus/mg-ismart-india-client. Thank you, John: this module would not exist without
// your work on the MG India protocol. The original licence is reproduced in NOTICE.md.

package dev.irshadm.volta.core.ismart

data class Vehicle(val vin: String, val series: String?, val model: String?, val colorName: String?, val isSubAccount: Boolean)

data class Tyres(val frontLeft: Double, val frontRight: Double, val rearLeft: Double, val rearRight: Double)

/**
 * Full status in real units (ported from `parse_status`). India is right-hand drive: the
 * protocol's `driverDoor` is the front-right door.
 */
data class VehicleSnapshot(
    val statusTime: Long,
    val socPercent: Int?,
    val rangeKm: Double?,
    val odometerKm: Double?,
    val locked: Boolean,
    val driverDoorOpen: Boolean,
    val passengerDoorOpen: Boolean,
    val rearLeftDoorOpen: Boolean,
    val rearRightDoorOpen: Boolean,
    val bootOpen: Boolean,
    val bonnetOpen: Boolean,
    val anyWindowOpen: Boolean,
    val lightsOn: Boolean,
    val climateRunning: Boolean,
    val interiorTempC: Int?,
    val exteriorTempC: Int?,
    val auxVolts: Double?,
    val tyres: Tyres?,
    val journeyId: Long,
    val journeyDistanceRaw: Int,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Int?,
    val gpsTime: Long?,
    val activeAlerts: List<Int>,
    /** Raw power mode / engine status: 0 when parked and off; non-zero assumed = ignition on. */
    val powerMode: Int = 0,
    val engineStatus: Int = 0,
    val speedKmh: Double? = null,
    /** Per window (India RHD: driver = front right). Null when the car doesn't report it. */
    val driverWindowOpen: Boolean? = null,
    val passengerWindowOpen: Boolean? = null,
    val rearLeftWindowOpen: Boolean? = null,
    val rearRightWindowOpen: Boolean? = null,
    val sunroofOpen: Boolean? = null,
    val sideLights: Boolean = false,
    val dippedBeam: Boolean = false,
    val mainBeam: Boolean = false,
    val handBrakeOn: Boolean? = null,
    val headingDeg: Int? = null,
) {
    companion object {

        fun fromStatus(f: Tap.StatusFrame): VehicleSnapshot {
            val b = f.basic
            var driverWindow = b.driverWindow == true
            // MG India sometimes reports a stale driver-window bit on a fully closed, locked car.
            if (driverWindow && b.locked && !(
                    b.driverDoor || b.passengerDoor || b.rearLeftDoor || b.rearRightDoor || b.boot || b.bonnet ||
                        b.passengerWindow == true || b.rearLeftWindow == true || b.rearRightWindow == true
                    )
            ) {
                driverWindow = false
            }
            val tyres = TyreScale.psi(listOf(b.tyreFrontLeft, b.tyreFrontRight, b.tyreRearLeft, b.tyreRearRight))
            val hasFix = f.position.gpsStatus >= 2
            return VehicleSnapshot(
                statusTime = f.statusTime,
                socPercent = b.fuelLevelPrc.takeIf { it in 0..100 },
                rangeKm = b.fuelRange / 10.0,
                odometerKm = b.mileage / 10.0,
                locked = b.locked,
                driverDoorOpen = b.driverDoor,
                passengerDoorOpen = b.passengerDoor,
                rearLeftDoorOpen = b.rearLeftDoor,
                rearRightDoorOpen = b.rearRightDoor,
                bootOpen = b.boot,
                bonnetOpen = b.bonnet,
                anyWindowOpen = driverWindow || b.passengerWindow == true || b.rearLeftWindow == true || b.rearRightWindow == true,
                lightsOn = b.sideLights || b.dippedBeam || b.mainBeam,
                climateRunning = b.remoteClimate in 2..3,
                interiorTempC = b.interiorTemp.takeIf { it in -60..90 },
                exteriorTempC = b.exteriorTemp.takeIf { it in -60..90 },
                auxVolts = b.batteryVoltage / 10.0,
                tyres = if (tyres.all { it != null }) Tyres(tyres[0]!!, tyres[1]!!, tyres[2]!!, tyres[3]!!) else null,
                journeyId = b.journeyId,
                journeyDistanceRaw = b.journeyDistance,
                latitude = if (hasFix) f.position.latitudeMicro / 1e6 else null,
                longitude = if (hasFix) f.position.longitudeMicro / 1e6 else null,
                altitudeM = if (hasFix) f.position.altitudeM else null,
                gpsTime = f.position.timeSeconds.takeIf { it > 0 },
                activeAlerts = f.alerts.filter { it.second != 0 }.map { it.first },
                powerMode = b.powerMode,
                engineStatus = b.engineStatus,
                speedKmh = f.position.speedTenths.takeIf { it >= 0 }?.div(10.0),
                driverWindowOpen = b.driverWindow?.let { driverWindow },
                passengerWindowOpen = b.passengerWindow,
                rearLeftWindowOpen = b.rearLeftWindow,
                rearRightWindowOpen = b.rearRightWindow,
                sunroofOpen = b.sunroof,
                sideLights = b.sideLights,
                dippedBeam = b.dippedBeam,
                mainBeam = b.mainBeam,
                handBrakeOn = b.handBrake,
                headingDeg = if (hasFix) f.position.heading else null,
            )
        }
    }
}

/** EV charging frame in real units (scales from `tap.py`). */
data class ChargeStatus(
    val statusTime: Long,
    val charging: Boolean,
    val pluggedIn: Boolean,
    val socPercent: Double?,
    val rangeKm: Double,
    val batteryEnergyKwh: Double,
    val voltageV: Double,
    val currentA: Double,
    val minutesRemaining: Int?,
    val odometerKm: Double,
    val distanceSinceChargeKm: Double?,
    val energySinceChargeKwh: Double?,
    val chargingType: Int,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val kmToday: Double? = null,
    val kwhToday: Double? = null,
) {
    /** Instantaneous charging power in kW (voltage × current), 0 when idle. */
    val powerKw: Double get() = if (charging) voltageV * currentA / 1000.0 else 0.0

    companion object {
        fun from(f: Tap.ChargeFrame) = ChargeStatus(
            statusTime = f.statusTime,
            charging = f.charging,
            pluggedIn = f.gunConnected,
            socPercent = f.powerLevelPrc?.takeIf { it in 0..1000 }?.let { it / 10.0 },
            rangeKm = f.fuelRange / 10.0,
            batteryEnergyKwh = f.realtimePower / 10.0,
            voltageV = f.voltage * 0.25,
            currentA = 1000.0 - f.current * 0.05,
            minutesRemaining = f.timeLevelPrc?.takeIf { it != 0xFFFF },
            odometerKm = f.mileage / 10.0,
            distanceSinceChargeKm = f.mileageSinceCharge?.let { it / 10.0 },
            energySinceChargeKwh = f.powerUsageSinceCharge?.let { it / 10.0 },
            chargingType = f.chargingType,
            startedAt = f.startTime?.takeIf { it > 0 },
            endedAt = f.endTime?.takeIf { it > 0 },
            kmToday = f.mileageOfDay?.let { it / 10.0 },
            kwhToday = f.powerUsageOfDay?.let { it / 10.0 },
        )
    }
}

/** Remote commands (type + params from `client.py`). */
enum class RemoteCommand(val label: String, val type: Int, val params: List<Pair<Int, Int>>) {
    Lock("Lock", 1, emptyList()),
    Unlock("Unlock", 2, listOf(4 to 0, 5 to 0, 6 to 0, 7 to 3, 255 to 0)),
    ClimateOn("AC on", 6, listOf(19 to 3, 20 to 3, 255 to 0)),
    ClimateOff("AC off", 6, listOf(19 to 0, 20 to 0, 255 to 0)),
    FindMyCar("Find my car", 0, listOf(1 to 1, 2 to 1, 3 to 1, 255 to 0)),
    OpenBoot("Open boot", 2, listOf(4 to 0, 5 to 0, 6 to 0, 7 to 2, 255 to 0)),

    // Windows: params 9–12 pick the windows (this car's WINDOW mask is 1100 = the two front ones), 13 = 3 open / 0 close.
    WindowsOpen("Open windows", 3, listOf(8 to 0, 9 to 1, 10 to 1, 11 to 0, 12 to 0, 13 to 3)),
    WindowsClose("Close windows", 3, listOf(8 to 0, 9 to 1, 10 to 1, 11 to 0, 12 to 0, 13 to 0)),
}

/**
 * Tyre pressures arrive as one raw number per wheel, scaled differently by car. The Windsor counts in 0.2 psi steps
 * (169 is 33.8 psi). The ZS EV and other SAIC cars count in 0.04 bar steps, as SAIC's own MQTT gateway reads them
 * (70 is 2.8 bar, about 41 psi). On the Windsor's scale a number under 100 is under 20 psi, so when every wheel reads
 * under 100 the car is using the bar scale. A flat tyre on a SAIC car still reads as one low wheel among normal ones.
 */
internal object TyreScale {
    private const val PSI_PER_UNIT = 0.2
    private const val BAR_PER_UNIT = 0.04
    private const val PSI_PER_BAR = 14.5038
    private const val BAR_SCALE_MAX_RAW = 99

    fun psi(raws: List<Int?>): List<Double?> {
        val valid = raws.map { raw -> raw?.takeIf { it in 1..255 } }
        val present = valid.filterNotNull()
        val barScale = present.isNotEmpty() && present.all { it <= BAR_SCALE_MAX_RAW }
        return valid.map { raw ->
            raw?.let {
                val psi = if (barScale) it * BAR_PER_UNIT * PSI_PER_BAR else it * PSI_PER_UNIT
                Math.round(psi * 10) / 10.0
            }
        }
    }
}
