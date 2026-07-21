package com.tolstykh.blindduel.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class HeadingSample(val headingDegrees: Float, val accuracy: Int)

/** Raw accelerometer x/y (m/s^2) — ambient decorative motion only (e.g. the duel screen's
 * particle backdrop swirl), never used for gameplay/aiming. */
data class TiltSample(val xAxis: Float, val yAxis: Float)

/**
 * Wraps the compass (TYPE_ROTATION_VECTOR) and step detector (TYPE_STEP_DETECTOR) sensors
 * used by the dead-reckoning bearing model. If [isStepDetectionAvailable] is false (sensor
 * absent, or the caller never observed a permission grant), [stepEvents] simply completes
 * without emitting — callers keep their step vector at zero and the bearing model degrades
 * to the heading-only estimate, per the plan's "graceful degradation" note.
 */
@Singleton
class MotionProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val isStepDetectionAvailable: Boolean get() = stepDetectorSensor != null

    /** Absolute compass heading in [0, 360), with the sensor's last-reported accuracy. */
    fun headingUpdates(): Flow<HeadingSample> = callbackFlow {
        val sensor = rotationSensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var lastAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                trySend(HeadingSample((azimuthDegrees + 360f).mod(360f), lastAccuracy))
            }

            override fun onAccuracyChanged(changedSensor: Sensor, accuracy: Int) {
                lastAccuracy = accuracy
            }
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /** Emits once per detected step; never emits if the sensor is unavailable. */
    fun stepEvents(): Flow<Unit> = callbackFlow {
        val sensor = stepDetectorSensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(Unit)
            }

            override fun onAccuracyChanged(changedSensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /** Never emits if the accelerometer is unavailable — callers keep their last/default tilt. */
    fun tiltUpdates(): Flow<TiltSample> = callbackFlow {
        val sensor = accelerometerSensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(TiltSample(event.values[0], event.values[1]))
            }

            override fun onAccuracyChanged(changedSensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}

fun isAccuracyLow(accuracy: Int): Boolean =
    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
