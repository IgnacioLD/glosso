package com.glosso.app

import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import com.glosso.app.designsystem.GlossoColors

/** Text-button colours whose label stays AA on the page (dialog actions, links). */
@Composable
fun accentTextButtonColors() = ButtonDefaults.textButtonColors(contentColor = GlossoColors.accentText)
