package com.fibrocoir.app.ecviewer

import androidx.compose.runtime.Composable
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Message

@Composable
fun EcViewerScreen(onBack: () -> Unit) {
    FcpScreen("EC Viewer", onBack = onBack) { Message("Coming in the next build") }
}
