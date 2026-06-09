package com.timothy.joystick

data class GestureData(
    val name: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)