package dev.kinash.smartscales.core.model

/**
 * A body segment an 8-electrode scale measures, and the [channel] its impedance is stored on: the order Chipsea
 * CloudV4 scales send them in (right arm, left arm, right leg, left leg, trunk).
 */
enum class Segment(val channel: Int) {
    RIGHT_ARM(1),
    LEFT_ARM(2),
    RIGHT_LEG(3),
    LEFT_LEG(4),
    TRUNK(5),
}
