package com.timothy.joystick

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView


// Oriental Lock (Lock to be horizontally oriented)
@Composable
private fun LockLandscape() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { original?.let { activity?.requestedOrientation = it } }
    }
}

@Composable
fun DPad(
    enabled: Boolean,
    onAxisChange: (String, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(160.dp), contentAlignment = Alignment.Center) {
        // UP
        GamepadImageButton(
            normalRes = R.drawable.dpad_top_press,
            pressedRes = R.drawable.dpad_top_default,
            enabled = enabled,
            onPress = { onAxisChange("LEFT_Y", -1f) },
            onRelease = { onAxisChange("LEFT_Y", 0f) },
            modifier = Modifier.align(Alignment.TopCenter).size(65.dp)
        )

        // DOWN
        GamepadImageButton(
            normalRes = R.drawable.dpad_bottom_press,
            pressedRes = R.drawable.dpad_bottom_default,
            enabled = enabled,
            onPress = { onAxisChange("LEFT_Y", 1f) },
            onRelease = { onAxisChange("LEFT_Y", 0f) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(65.dp)
                .graphicsLayer { rotationZ = 0f }
        )

        // LEFT
        GamepadImageButton(
            normalRes = R.drawable.dpad_left_press,
            pressedRes = R.drawable.dpad_left_default,
            enabled = enabled,
            onPress = { onAxisChange("LEFT_X", -1f) },
            onRelease = { onAxisChange("LEFT_X", 0f) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(65.dp)
                .graphicsLayer { rotationZ = 0f }
        )

        // RIGHT
        GamepadImageButton(
            normalRes = R.drawable.dpad_right_press,
            pressedRes = R.drawable.dpad_right_default,
            enabled = enabled,
            onPress = { onAxisChange("LEFT_X", 1f) },
            onRelease = { onAxisChange("LEFT_X", 0f) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(65.dp)
                .graphicsLayer { rotationZ = 0f }
        )
    }
}

// Root screen
@Composable
fun JoystickScreen(
    connectionState: UDPManager.ConnectionState?,
    onDisconnect: () -> Unit,
    onButtonPress: (String) -> Unit,
    onButtonRelease: (String) -> Unit,
    onAxisChange: (String, Float) -> Unit
) {
    LockLandscape()

    val controlsEnabled = connectionState is UDPManager.ConnectionState.Connected

    val (statusText, statusColor) = when (connectionState) {
        is UDPManager.ConnectionState.Connected   -> "Connected"                         to Color(0xFF43A047)
        is UDPManager.ConnectionState.Connecting  -> "Connecting…"                       to Color(0xFFFB8C00)
        is UDPManager.ConnectionState.Error       -> "Error: ${connectionState.message}" to Color(0xFFE53935)
        else                                             -> "Disconnected"                     to Color(0xFFE53935)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            rotate(-22f, pivot = Offset.Zero) {
                drawRect(
                    color = Color(0xFFD32F2F),
                    topLeft = Offset(-200f, -600f),
                    size = Size(920f, 3000f),
                    alpha = 0.92f
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(720f, -600f),
                    size = Size(8f, 3000f),
                    alpha = 0.85f
                )
            }
        }

        // --- ORIGINAL LAYOUT PRESERVED ---
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LeftPanel(
                enabled          = controlsEnabled,
                onButtonPress    = onButtonPress,
                onButtonRelease  = onButtonRelease,
                onAxisChange     = onAxisChange,
                modifier         = Modifier.weight(1f).fillMaxHeight()
            )

            Spacer(Modifier.width(8.dp))

            CenterPanel(
                statusText      = statusText,
                statusColor     = statusColor,
                enabled         = controlsEnabled,
                onDisconnect    = onDisconnect,
                onButtonPress   = onButtonPress,
                onButtonRelease = onButtonRelease,
                modifier        = Modifier.weight(1.4f).fillMaxHeight()
            )

            Spacer(Modifier.width(8.dp))

            RightPanel(
                enabled           = controlsEnabled,
                onButtonPress     = onButtonPress,
                onButtonRelease   = onButtonRelease,
                modifier          = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

// Left Panel (LB and Left Stick)
@Composable
private fun LeftPanel(
    enabled: Boolean,
    onButtonPress: (String) -> Unit,
    onButtonRelease: (String) -> Unit,
    onAxisChange: (String, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        GamepadImageButton(
            normalRes  = R.drawable.button_lb_base,
            pressedRes = R.drawable.button_lb_pressed,
            enabled    = enabled,
            onPress    = { onButtonPress("LB") },
            onRelease  = { onButtonRelease("LB") },
            modifier   = Modifier.size(120.dp)
        )

        Box(modifier = Modifier.graphicsLayer { rotationZ = -4f }) {
            DPad(
                enabled = enabled,
                onAxisChange = onAxisChange
            )
        }
    }
}

// Center Panel
@Composable
private fun CenterPanel(
    statusText: String,
    statusColor: Color,
    enabled: Boolean,
    onDisconnect: () -> Unit,
    onButtonPress: (String) -> Unit,
    onButtonRelease: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Status + BACK Button Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .graphicsLayer { rotationZ = -2f }
                    .background(Color(0xFF0A0A0A), CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp))
                    .border(2.dp, statusColor, CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = statusText.uppercase(),
                    color = statusColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle = FontStyle.Italic
                )
            }
            Box(
                modifier = Modifier
                    .graphicsLayer { rotationZ = 2f }
                    .background(Color(0xFFD32F2F), CutCornerShape(topEnd = 16.dp, bottomStart = 16.dp))
                    .clickable(enabled = true, onClick = { onDisconnect() })
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "BACK",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle = FontStyle.Italic
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            GamepadImageButton(
                normalRes  = R.drawable.button_back_base,
                pressedRes = R.drawable.button_back_pressed,
                enabled    = enabled,
                onPress    = { onButtonPress("BACK") },
                onRelease  = { onButtonRelease("BACK") },
                modifier   = Modifier
                    .width(120.dp)
                    .height(90.dp)
            )

            GamepadImageButton(
                normalRes  = R.drawable.button_start_base,
                pressedRes = R.drawable.button_start_pressed,
                enabled    = enabled,
                onPress    = { onButtonPress("START") },
                onRelease  = { onButtonRelease("START") },
                modifier   = Modifier
                    .width(120.dp)
                    .height(90.dp)
            )
        }
    }
}

// Right Panel
@Composable
private fun RightPanel(
    enabled: Boolean,
    onButtonPress: (String) -> Unit,
    onButtonRelease: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        GamepadImageButton(
            normalRes  = R.drawable.button_rb_base,
            pressedRes = R.drawable.button_rb_pressed,
            enabled    = enabled,
            onPress    = { onButtonPress("RB") },
            onRelease  = { onButtonRelease("RB") },
            modifier   = Modifier.size(120.dp)
        )
        FaceButtonCluster(
            enabled         = enabled,
            onButtonPress   = onButtonPress,
            onButtonRelease = onButtonRelease,
            modifier        = Modifier.graphicsLayer { rotationZ = 6f }
        )
    }
}

// Face button cluster (Y,X,B,A)
@Composable
private fun FaceButtonCluster(
    enabled: Boolean,
    onButtonPress: (String) -> Unit,
    onButtonRelease: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(155.dp)) {
        GamepadImageButton(
            normalRes  = R.drawable.button_y_base,
            pressedRes = R.drawable.button_y_pressed,
            enabled = enabled,
            onPress = { onButtonPress("Y") },
            onRelease = { onButtonRelease("Y") },
            modifier = Modifier.size(55.dp).align(Alignment.TopCenter)
        )
        GamepadImageButton(
            normalRes  = R.drawable.button_x_base,
            pressedRes = R.drawable.button_x_pressed,
            enabled = enabled,
            onPress = { onButtonPress("X") },
            onRelease = { onButtonRelease("X") },
            modifier = Modifier.size(55.dp).align(Alignment.CenterStart)
        )
        GamepadImageButton(
            normalRes  = R.drawable.button_b_base,
            pressedRes = R.drawable.button_b_pressed,
            enabled = enabled,
            onPress = { onButtonPress("B") },
            onRelease = { onButtonRelease("B") },
            modifier = Modifier.size(55.dp).align(Alignment.CenterEnd)
        )
        GamepadImageButton(
            normalRes  = R.drawable.button_a_base,
            pressedRes = R.drawable.button_a_pressed,
            enabled = enabled,
            onPress = { onButtonPress("A") },
            onRelease = { onButtonRelease("A") },
            modifier = Modifier.size(55.dp).align(Alignment.BottomCenter)
        )
    }
}

// System Pill (Start and Back) - Kept original logic
@Composable
private fun SystemPillButton(
    label: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    tint: Color = Color(0xFF424242),
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }

    val bgColor = when {
        !enabled -> Color(0xFF2A2A2A)
        pressed  -> Color(
            red   = (tint.red   * 0.70f).coerceIn(0f, 1f),
            green = (tint.green * 0.70f).coerceIn(0f, 1f),
            blue  = (tint.blue  * 0.70f).coerceIn(0f, 1f)
        )
        else     -> tint
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .widthIn(min = 64.dp)
            .height(30.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .background(color = bgColor, shape = RoundedCornerShape(50))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).also { it.consume() }
                    pressed = true
                    onPress()
                    waitForUpOrCancellation()
                    pressed = false
                    onRelease()
                }
            }
    ) {
        Text(
            text       = label,
            color      = Color.White,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 10.dp)
        )
    }
}