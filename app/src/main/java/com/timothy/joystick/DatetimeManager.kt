package com.timothy.joystick

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DatetimeManager {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun now(): String {
        val datetime = LocalDateTime.now().format(formatter)
        return datetime
    }
}