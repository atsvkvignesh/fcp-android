package com.fibrocoir.app.inventory

import androidx.compose.runtime.Composable
import com.fibrocoir.app.ui.FcpScreen
import com.fibrocoir.app.ui.Message

@Composable
fun InventoryScreen(onBack: () -> Unit) {
    FcpScreen("Stores inventory", onBack = onBack) { Message("Coming in the next build") }
}
