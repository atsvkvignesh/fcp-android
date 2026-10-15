package com.fibrocoir.app.eclog

import androidx.compose.runtime.Composable
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Message

@Composable
fun EcLogScreen(onBack: () -> Unit) {
    FcpScreen("EC Log", onBack = onBack) { Message("Coming in the next build") }
}
