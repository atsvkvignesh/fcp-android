package com.fibrocoir.app.fleet

import androidx.compose.runtime.Composable
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Message

@Composable
fun FleetScreen(onBack: () -> Unit) {
    FcpScreen("Fleet manager", onBack = onBack) { Message("Coming in the next build") }
}
