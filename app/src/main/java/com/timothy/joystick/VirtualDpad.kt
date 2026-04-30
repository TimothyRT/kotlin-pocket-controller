package com.timothy.joystick

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot

enum class DPadDirection { NONE, UP, DOWN, LEFT, RIGHT }

@Composable
fun VirtualDPad(
    modifier: Modifier = Modifier,
    onDirectionChanged: (DPadDirection) -> Unit
) {
    var currentDirection by remember { mutableStateOf(DPadDirection.NONE) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val drawableId = when (currentDirection) {
        DPadDirection.NONE  -> R.drawable.dpad_base
        DPadDirection.UP    -> R.drawable.dpad_top
        DPadDirection.DOWN  -> R.drawable.dpad_bottom
        DPadDirection.LEFT  -> R.drawable.dpad_left
        DPadDirection.RIGHT -> R.drawable.dpad_right
    }

    Image(
        painter = painterResource(id = drawableId),
        contentDescription = "D-Pad",
        modifier = modifier
            .size(150.dp)
            .onGloballyPositioned { size = it.size }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()

                    // Loop while the user is holding down their finger
                    do {
                        val event = awaitPointerEvent()
                        val position = event.changes.firstOrNull()?.position

                        if (position != null && event.changes.any { it.pressed }) {
                            val newDir = calculateDPadDirection(
                                x = position.x,
                                y = position.y,
                                width = size.width.toFloat(),
                                height = size.height.toFloat()
                            )

                            if (newDir != currentDirection) {
                                currentDirection = newDir
                                onDirectionChanged(newDir)
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // Finger released
                    if (currentDirection != DPadDirection.NONE) {
                        currentDirection = DPadDirection.NONE
                        onDirectionChanged(DPadDirection.NONE)
                    }
                }
            }
    )
}

private fun calculateDPadDirection(x: Float, y: Float, width: Float, height: Float): DPadDirection {
    val cx = width / 2
    val cy = height / 2
    val dx = x - cx
    val dy = y - cy

    // Small Deadzone
    val deadzone = width * 0.15f
    if (hypot(dx.toDouble(), dy.toDouble()) < deadzone) return DPadDirection.NONE

    // Calculate angle in degrees
    val angle = atan2(dy.toDouble(), dx.toDouble()) * (180 / Math.PI)

    return when {
        angle >= -45 && angle < 45 -> DPadDirection.RIGHT
        angle >= 45 && angle < 135 -> DPadDirection.DOWN
        angle >= -135 && angle < -45 -> DPadDirection.UP
        else -> DPadDirection.LEFT
    }
}