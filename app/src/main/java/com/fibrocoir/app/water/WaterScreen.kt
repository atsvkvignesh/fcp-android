package com.fibrocoir.app.water

import androidx.compose.runtime.Composable
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Message

@Composable
fun WaterScreen(onBack: () -> Unit) {
    FcpScreen("Water record", onBack = onBack) { Message("Coming in the next build") }
}
