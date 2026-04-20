package com.timothy.joystick

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource

@Composable
fun GamepadImageButton(
    normalRes: Int,
    pressedRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }

    Image(
        painter = painterResource(id = if (isPressed && enabled) pressedRes else normalRes),
        contentDescription = null,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.35f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).also { it.consume() }
                    isPressed = true
                    onPress()
                    waitForUpOrCancellation()
                    isPressed = false
                    onRelease()
                }
            }
    )
}