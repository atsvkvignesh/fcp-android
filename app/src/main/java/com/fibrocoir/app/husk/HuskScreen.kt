package com.fibrocoir.app.husk

import androidx.compose.runtime.Composable
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Message

@Composable
fun HuskScreen(onBack: () -> Unit) {
    FcpScreen("Raw material check", onBack = onBack) { Message("Coming in the next build") }
}
